package app.morphed.extension.twitch.adblock;

import app.morphed.extension.twitch.hls.MasterPlaylist;
import app.morphed.extension.twitch.hls.MediaPlaylist;
import app.morphed.extension.twitch.hls.UrlKey;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Finds an ad-free media playlist for the same channel and quality by requesting the stream
 * as another player type. Port of vaft's backup stream search.
 */
public final class BackupStreamFinder {
    public static final class Result {
        public final String playerType;
        public final String playlist;

        Result(String playerType, String playlist) {
            this.playerType = playerType;
            this.playlist = playlist;
        }
    }

    /** Source quality types first, then 360p autoplay as the last resort, as in vaft. */
    static final List<String> PLAYER_TYPES = Collections.unmodifiableList(
            Arrays.asList("site", "popout", "mobile_web", "embed", "autoplay"));

    private static final long FAILED_TYPE_RETRY_MS = 15_000;
    /** The player waits on our answer, so give up well before its 10 second request timeout. */
    private static final long SEARCH_BUDGET_MS = 6_000;

    private final Http http;
    private final AccessTokenClient tokens;
    private final Logger logger;
    private final LongSupplier clock;
    private final Problem.Reporter problems;
    /** Why the last backup master playlist could not be fetched. */
    private String lastError;

    public BackupStreamFinder(Http http, AccessTokenClient tokens, Logger logger, LongSupplier clock, Problem.Reporter problems) {
        this.http = http;
        this.tokens = tokens;
        this.logger = logger;
        this.clock = clock;
        this.problems = problems;
    }

    /** A clean playlist for {@code target}, or null when every player type has ads too. */
    Result find(StreamSession session, MasterPlaylist.Variant target) {
        String key = UrlKey.of(target.url);
        String committedUrl = session.backupMediaUrls.get(key);
        if (committedUrl != null) {
            String playlist = fetchCleanPlaylist(committedUrl);
            if (playlist != null) return new Result(session.backupPlayerType, playlist);
            session.backupMediaUrls.remove(key);
        }

        long deadline = clock.getAsLong() + SEARCH_BUDGET_MS;
        int tried = 0;
        int failed = 0;
        for (String playerType : orderedPlayerTypes(session)) {
            if (clock.getAsLong() > deadline) {
                logger.log("Backup search ran out of time");
                break;
            }
            Long failedAt = session.failedPlayerTypes.get(playerType);
            if (failedAt != null && clock.getAsLong() - failedAt < FAILED_TYPE_RETRY_MS) continue;

            tried++;
            Result result = tryPlayerType(session, target, playerType);
            if (result != null) return result;
            if (session.failedPlayerTypes.containsKey(playerType)) failed++;
        }
        // Every type erroring (rather than having ads) points at a changed token or usher API.
        if (tried > 0 && failed == tried) problems.report(Problem.BACKUPS_UNAVAILABLE, lastError);
        return null;
    }

    private Result tryPlayerType(StreamSession session, MasterPlaylist.Variant target, String playerType) {
        // A cached master playlist can go stale; when it does, retry once with a fresh token.
        for (int attempt = 0; attempt < 2; attempt++) {
            String master = session.backupMasters.get(playerType);
            boolean fresh = master == null;
            if (fresh) {
                master = fetchMaster(session, playerType);
                if (master == null) {
                    session.failedPlayerTypes.put(playerType, clock.getAsLong());
                    return null;
                }
                session.backupMasters.put(playerType, master);
            }

            MasterPlaylist.Variant variant = MasterPlaylist.parse(master).closestTo(target);
            if (variant != null) {
                String playlist = fetchCleanPlaylist(variant.url);
                if (playlist != null) {
                    session.backupMediaUrls.put(UrlKey.of(target.url), variant.url);
                    session.backupPlayerType = playerType;
                    session.preferredPlayerType = playerType;
                    logger.log("Using " + playerType + " playlist " + variant.resolution + " for " + session.channel);
                    return new Result(playerType, playlist);
                }
                logger.log("Backup " + playerType + " also has ads");
            }

            session.backupMasters.remove(playerType);
            if (fresh) break;
        }
        return null;
    }

    private List<String> orderedPlayerTypes(StreamSession session) {
        List<String> types = new ArrayList<>(PLAYER_TYPES);
        if (session.preferredPlayerType != null && types.remove(session.preferredPlayerType)) {
            types.add(0, session.preferredPlayerType);
        }
        return types;
    }

    private String fetchMaster(StreamSession session, String playerType) {
        try {
            AccessTokenClient.Token token = tokens.fetch(session.channel, playerType);
            if (token == null) {
                lastError = "no access token for " + playerType;
                logger.log(lastError);
                return null;
            }
            Http.Response response = http.get(usherUrl(session.masterUrl, token), Collections.emptyMap());
            if (!response.isOk()) {
                lastError = "usher returned " + response.status + " for " + playerType;
                logger.log(lastError);
                return null;
            }
            return response.body;
        } catch (IOException e) {
            lastError = playerType + ": " + e.getMessage();
            logger.log("Backup " + lastError);
            return null;
        }
    }

    private String fetchCleanPlaylist(String url) {
        try {
            Http.Response response = http.get(url, Collections.emptyMap());
            if (!response.isOk()) return null;
            MediaPlaylist playlist = MediaPlaylist.parse(response.body);
            if (playlist.segments.isEmpty() || playlist.hasAdSegments()) return null;
            return response.body;
        } catch (IOException e) {
            return null;
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
