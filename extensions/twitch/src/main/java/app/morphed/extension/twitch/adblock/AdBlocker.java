package app.morphed.extension.twitch.adblock;

import app.morphed.extension.twitch.hls.AdMarkers;
import app.morphed.extension.twitch.hls.MasterPlaylist;
import app.morphed.extension.twitch.hls.MediaPlaylist;
import app.morphed.extension.twitch.hls.PlaylistStitcher;
import app.morphed.extension.twitch.hls.UrlKey;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites the live playlists the player downloads so it never receives ad segments.
 * <ul>
 *     <li>Master playlist: remembered so each quality can be matched to a backup later.</li>
 *     <li>Media playlist without ads: passed through.</li>
 *     <li>Ad markers only (client side ad): markers removed so the app does not start the ad.</li>
 *     <li>Ad segments (stitched ad): replaced by a clean backup playlist; if none exists the ad
 *     segments are cut out and the player waits for live content.</li>
 * </ul>
 */
public final class AdBlocker {
    private static final Pattern CHANNEL = Pattern.compile("/channel/hls/([^/?]+)\\.m3u8");
    private static final long SESSION_MAX_IDLE_MS = 30 * 60 * 1000;
    private static final long REASSIGNMENT_WINDOW_MS = 30 * 1000;

    private final BackupStreamFinder backups;
    private final Logger logger;
    private final LongSupplier clock;
    /** Runs when a break whose ad segments were swapped or cut out is over. */
    private final Runnable afterSwappedBreak;
    private final Problem.Reporter problems;
    private final Map<String, StreamSession> sessionsByVariant = new ConcurrentHashMap<>();

    public AdBlocker(BackupStreamFinder backups, Logger logger, LongSupplier clock,
                     Runnable afterSwappedBreak, Problem.Reporter problems) {
        this.backups = backups;
        this.logger = logger;
        this.clock = clock;
        this.afterSwappedBreak = afterSwappedBreak;
        this.problems = problems;
    }

    /**
     * A live usher playlist this blocker does not recognise, which means Twitch changed its usher
     * paths and streams bypass the ad blocker.
     */
    public boolean isUnrecognizedLivePlaylist(String url) {
        if (url == null || shouldInspect(url)) return false;
        String path = UrlKey.of(url);
        return path.contains("usher.") && path.endsWith(".m3u8")
                && !path.contains("/vod/") && !url.contains("picture-by-picture");
    }

    /** Called for requests that are not inspected, to notice playlists that slip past. */
    public void checkRequest(String url) {
        if (isUnrecognizedLivePlaylist(url)) problems.report(Problem.UNRECOGNIZED_PLAYLIST, UrlKey.of(url));
    }

    public boolean isMasterPlaylist(String url) {
        return url.contains("usher.ttvnw.net") && CHANNEL.matcher(url).find() && !url.contains("picture-by-picture");
    }

    public boolean isKnownMediaPlaylist(String url) {
        return sessionsByVariant.containsKey(UrlKey.of(url));
    }

    /** A live media playlist on Twitch's playlist servers, known or not. */
    static boolean isLiveMediaPlaylist(String url) {
        String path = UrlKey.of(url);
        return path.contains("video-weaver.") && path.endsWith(".m3u8");
    }

    public boolean shouldInspect(String url) {
        return url != null && (isMasterPlaylist(url) || isKnownMediaPlaylist(url) || isLiveMediaPlaylist(url));
    }

    /** Returns the playlist to hand to the player in place of {@code body}. */
    public String process(String url, String body) {
        if (isMasterPlaylist(url)) {
            onMasterPlaylist(url, body);
            return body;
        }
        StreamSession session = sessionsByVariant.get(UrlKey.of(url));
        if (session == null) session = adoptReassignedPlaylist(url);
        if (session == null) {
            problems.report(Problem.UNRECOGNIZED_PLAYLIST, UrlKey.of(url));
            return body;
        }
        return onMediaPlaylist(session, url, body);
    }

    /**
     * Twitch can move a running stream to another playlist server. The new URL is not in the master
     * playlist, so attach it to the quality that was playing most recently.
     */
    private StreamSession adoptReassignedPlaylist(String url) {
        if (!isLiveMediaPlaylist(url)) return null;
        long now = clock.getAsLong();
        StreamSession latest = null;
        for (StreamSession session : sessionsByVariant.values()) {
            if (session.lastVariantUrl == null || now - session.lastSeen > REASSIGNMENT_WINDOW_MS) continue;
            if (latest == null || session.lastSeen > latest.lastSeen) latest = session;
        }
        if (latest == null) return null;
        synchronized (latest) {
            latest.aliases.put(UrlKey.of(url), latest.lastVariantUrl);
        }
        sessionsByVariant.put(UrlKey.of(url), latest);
        logger.log("Playlist moved to " + UrlKey.of(url));
        return latest;
    }

    private void onMasterPlaylist(String url, String body) {
        Matcher matcher = CHANNEL.matcher(url);
        if (!matcher.find()) return;

        long now = clock.getAsLong();
        forgetIdleSessions(now);

        String channel = matcher.group(1).toLowerCase();
        MasterPlaylist master = MasterPlaylist.parse(body);
        StreamSession session = new StreamSession(channel, url, master, now);
        for (MasterPlaylist.Variant variant : master.variants) {
            sessionsByVariant.put(UrlKey.of(variant.url), session);
        }
        logger.log("Watching " + channel + " (" + master.variants.size() + " qualities)");
    }

    private String onMediaPlaylist(StreamSession session, String url, String body) {
        synchronized (session) {
            session.lastSeen = clock.getAsLong();
            session.lastVariantUrl = session.variantUrlFor(url);
            MediaPlaylist playlist = MediaPlaylist.parse(body);
            PlaylistStitcher stitcher = session.stitcherFor(url);

            if (!playlist.hasAds()) {
                Set<String> suspicious = AdMarkers.suspiciousMarkers(body);
                if (!suspicious.isEmpty()) {
                    problems.report(Problem.UNRECOGNIZED_AD_MARKERS, String.join(", ", suspicious));
                }
                boolean refresh = false;
                if (session.inAdBreak) {
                    logger.log("Ad break over on " + session.channel);
                    refresh = session.swappedThisBreak;
                    session.endAdBreak();
                }
                String output = stitcher.process(playlist, body, PlaylistStitcher.NATIVE);
                if (refresh) afterSwappedBreak.run();
                return output;
            }

            if (!session.inAdBreak) {
                session.inAdBreak = true;
                logger.log("Ad break on " + session.channel + (playlist.hasAdSegments() ? " (stitched)" : " (markers only)"));
            }

            if (playlist.hasAdSegments()) {
                session.swappedThisBreak = true;
                MasterPlaylist.Variant variant = session.master.byUrl(session.variantUrlFor(url));
                BackupStreamFinder.Result backup = variant == null ? null : backups.find(session, variant);
                if (backup != null) {
                    return stitcher.process(MediaPlaylist.parse(backup.playlist), backup.playlist, "backup:" + backup.playerType);
                }
            }
            return stitcher.process(playlist, body, PlaylistStitcher.NATIVE);
        }
    }

    private void forgetIdleSessions(long now) {
        Iterator<StreamSession> iterator = sessionsByVariant.values().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().lastSeen > SESSION_MAX_IDLE_MS) iterator.remove();
        }
    }
}
