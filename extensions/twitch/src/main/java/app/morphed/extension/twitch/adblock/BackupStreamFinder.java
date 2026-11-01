package app.morphed.extension.twitch.adblock;

import app.morphed.extension.twitch.hls.MasterPlaylist;
import app.morphed.extension.twitch.hls.MediaPlaylist;
import app.morphed.extension.twitch.hls.UrlKey;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * Finds an ad-free media playlist for the same channel and quality by requesting the stream
 * as other player types. Port of vaft's backup stream search, but all types are tried at once
 * so opening a channel during an ad does not wait on them one by one.
 */
public final class BackupStreamFinder {
    public static final class Result {
        public final String playerType;
        public final String playlist;
        /** Where the playlist came from; its relative URIs resolve against this. */
        public final String url;
        /** For example {@code 640x360}, or null for audio only. */
        public final String resolution;
        /** The quality (master playlist URL) this backup stands in for. */
        final String target;

        Result(String playerType, String playlist, String url, String resolution, String target) {
            this.playerType = playerType;
            this.playlist = playlist;
            this.url = url;
            this.resolution = resolution;
            this.target = target;
        }
    }

    /** Preference order: source quality types first, then 360p autoplay as the last resort, as in vaft. */
    static final List<String> PLAYER_TYPES = Collections.unmodifiableList(
            Arrays.asList("site", "popout", "mobile_web", "embed", "autoplay"));

    private static final long FAILED_TYPE_RETRY_MS = 15_000;
    private static final long EMPTY_SEARCH_COOLDOWN_MS = 10_000;
    /** How long a search may run in the background; the player does not wait for all of it. */
    static final long SEARCH_BUDGET_MS = 8_000;
    /**
     * How long a playlist poll waits for a search. Segments are 2 seconds long, so a longer wait
     * starves the player; the poll cuts the ad out instead and the next one uses what was found.
     */
    static final long PLAYER_WAIT_MS = 1_500;
    /** How long a poll waits to refresh the backup already in use. */
    static final long REFRESH_TIMEOUT_MS = 2_000;
    /** Requests that outlive their budget keep a thread until their own timeout; this caps how many. */
    private static final int MAX_THREADS = 8;

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(MAX_THREADS, runnable -> {
        Thread thread = new Thread(runnable, "TwitchMorphed-backup");
        thread.setDaemon(true);
        return thread;
    });
    /** Runs whole searches (one per stream at a time), which then fan out on {@link #EXECUTOR}. */
    private static final ExecutorService SEARCHES = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "TwitchMorphed-search");
        thread.setDaemon(true);
        return thread;
    });

    /** Outcome of trying one player type, computed off the session lock and applied afterwards. */
    private static final class Attempt {
        /** Master playlist to keep for next time, or null to forget the cached one. */
        String master;
        boolean failed;
        String error;
        Result result;
    }

    private final Http http;
    private final AccessTokenClient tokens;
    private final Logger logger;
    private final LongSupplier clock;
    private final Problem.Reporter problems;

    public BackupStreamFinder(Http http, AccessTokenClient tokens, Logger logger, LongSupplier clock, Problem.Reporter problems) {
        this.http = http;
        this.tokens = tokens;
        this.logger = logger;
        this.clock = clock;
        this.problems = problems;
    }

    /**
     * A clean playlist for {@code target}, or null for now: when every player type has ads too, or
     * when the search takes longer than the player should wait. A search that runs over keeps going
     * in the background and stores what it finds, so the next poll (about 2 seconds later) uses it.
     */
    Result find(StreamSession session, MasterPlaylist.Variant target) {
        String key = UrlKey.of(target.url);
        Result committed = session.backupResults.get(key);
        if (committed != null) {
            Future<String> refresh = EXECUTOR.submit(() -> fetchPlaylist(committed.url));
            String playlist;
            try {
                playlist = refresh.get(REFRESH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                refresh.cancel(true);
                logger.log("Backup " + committed.playerType + " too slow to refresh");
                return null;
            } catch (InterruptedException | ExecutionException e) {
                return null;
            }
            if (isClean(playlist)) return new Result(committed.playerType, playlist, committed.url, committed.resolution, committed.target);
            session.backupResults.remove(key);
        }

        Future<Result> search;
        synchronized (session.searchLock) {
            if (session.pendingSearch != null && session.pendingSearch.isDone()) session.pendingSearch = null;
            if (session.pendingSearch == null) {
                // The player asks for a new playlist every couple of seconds. When no player type was
                // ad-free, searching again right away only floods Twitch with requests, which it
                // answers by throttling the whole app (chat included).
                if (clock.getAsLong() - session.lastEmptySearchAt < EMPTY_SEARCH_COOLDOWN_MS) {
                    logger.log("Backup search paused (nothing ad-free " + (clock.getAsLong() - session.lastEmptySearchAt) + "ms ago)");
                    return null;
                }
                session.pendingSearch = SEARCHES.submit(() -> search(session, target));
            }
            search = session.pendingSearch;
        }
        try {
            Result result = search.get(PLAYER_WAIT_MS, TimeUnit.MILLISECONDS);
            // A search started for another quality stored its result for that one.
            return result != null && key.equals(UrlKey.of(result.target)) ? result : null;
        } catch (TimeoutException e) {
            logger.log("Backup search still running, cutting the ad out meanwhile");
            return null;
        } catch (InterruptedException | ExecutionException e) {
            return null;
        }
    }

    /** Tries every player type at once and stores the best clean one for {@code target}. */
    private Result search(StreamSession session, MasterPlaylist.Variant target) {
        String key = UrlKey.of(target.url);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SEARCH_BUDGET_MS);
        Map<String, Future<Attempt>> attempts = new LinkedHashMap<>();
        for (String playerType : PLAYER_TYPES) {
            Long failedAt = session.failedPlayerTypes.get(playerType);
            if (failedAt != null && clock.getAsLong() - failedAt < FAILED_TYPE_RETRY_MS) continue;
            String cachedMaster = session.backupMasters.get(playerType);
            attempts.put(playerType, EXECUTOR.submit(() -> attempt(session, cachedMaster, target, playerType)));
        }
        if (attempts.isEmpty()) return null;

        Result best = null;
        int failed = 0;
        String lastError = null;
        for (Map.Entry<String, Future<Attempt>> entry : attempts.entrySet()) {
            String playerType = entry.getKey();
            Attempt attempt;
            try {
                attempt = entry.getValue().get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                entry.getValue().cancel(true);
                logger.log("Backup " + playerType + " ran out of time");
                continue;
            } catch (InterruptedException | ExecutionException e) {
                continue;
            }

            if (attempt.failed) {
                session.failedPlayerTypes.put(playerType, clock.getAsLong());
                failed++;
                lastError = attempt.error;
                logger.log("Backup " + attempt.error);
            }
            if (attempt.master != null) session.backupMasters.put(playerType, attempt.master);
            else session.backupMasters.remove(playerType);

            // Types are visited in preference order, so the first clean one is the best quality: use it
            // at once instead of waiting for slower, lower quality types.
            if (attempt.result != null) {
                best = attempt.result;
                break;
            }
            if (!attempt.failed) logger.log("Backup " + playerType + " also has ads");
        }

        // Every type erroring (rather than having ads) points at a changed token or usher API.
        if (best == null && failed == attempts.size()) problems.report(Problem.BACKUPS_UNAVAILABLE, lastError);
        if (best != null) {
            session.backupResults.put(key, best);
            logger.log("Using " + best.playerType + " playlist " + best.resolution + " for " + session.channel);
        } else {
            session.lastEmptySearchAt = clock.getAsLong();
        }
        return best;
    }

    private Attempt attempt(StreamSession session, String cachedMaster, MasterPlaylist.Variant target, String playerType) {
        Attempt attempt = new Attempt();
        String master = cachedMaster;
        // A cached master playlist can expire; only then is it worth asking for a fresh token.
        // A playlist that merely has ads keeps its master: the ad is in the stream, not the token.
        for (int round = 0; round < 2; round++) {
            boolean fresh = master == null;
            if (fresh) {
                try {
                    master = fetchMaster(session, playerType);
                } catch (IOException e) {
                    attempt.failed = true;
                    attempt.error = playerType + ": " + e.getMessage();
                    return attempt;
                }
            }
            attempt.master = master;

            MasterPlaylist.Variant variant = MasterPlaylist.parse(master).closestTo(target);
            if (variant == null) return attempt;
            String url = resolve(session.masterUrl, variant.url);
            String playlist = fetchPlaylist(url);
            if (playlist != null) {
                if (isClean(playlist)) attempt.result = new Result(playerType, playlist, url, variant.resolution, target.url);
                return attempt;
            }

            // The media playlist could not be loaded: the cached master has expired.
            attempt.master = null;
            master = null;
            if (fresh) break;
        }
        return attempt;
    }

    private String fetchMaster(StreamSession session, String playerType) throws IOException {
        AccessTokenClient.Token token = tokens.fetch(session.channel, playerType);
        if (token == null) throw new IOException("no access token");
        Http.Response response = http.get(usherUrl(session.masterUrl, token), Collections.emptyMap());
        if (!response.isOk()) throw new IOException("usher returned " + response.status);
        return response.body;
    }

    /** The media playlist, or null when it could not be loaded. */
    private String fetchPlaylist(String url) {
        try {
            Http.Response response = http.get(url, Collections.emptyMap());
            return response.isOk() ? response.body : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isClean(String playlist) {
        if (playlist == null) return false;
        MediaPlaylist parsed = MediaPlaylist.parse(playlist);
        return !parsed.segments.isEmpty() && !parsed.hasAdSegments();
    }

    private static String resolve(String baseUrl, String url) {
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        try {
            return URI.create(baseUrl).resolve(url).toString();
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    /** The app's own usher URL with the token and signature swapped for the backup ones. */
    static String usherUrl(String masterUrl, AccessTokenClient.Token token) {
        int queryStart = masterUrl.indexOf('?');
        String base = queryStart >= 0 ? masterUrl.substring(0, queryStart) : masterUrl;
        StringBuilder query = new StringBuilder();
        if (queryStart >= 0) {
            for (String parameter : masterUrl.substring(queryStart + 1).split("&")) {
                if (parameter.isEmpty() || parameter.startsWith("token=") || parameter.startsWith("sig=")) continue;
                query.append(parameter).append('&');
            }
        }
        query.append("sig=").append(encode(token.signature)).append("&token=").append(encode(token.value));
        return base + "?" + query;
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
