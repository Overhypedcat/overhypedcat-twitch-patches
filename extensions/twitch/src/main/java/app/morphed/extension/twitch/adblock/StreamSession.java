package app.morphed.extension.twitch.adblock;

import app.morphed.extension.twitch.hls.MasterPlaylist;
import app.morphed.extension.twitch.hls.PlaylistStitcher;
import app.morphed.extension.twitch.hls.UrlKey;

import java.util.HashMap;
import java.util.Map;

/**
 * State for one loaded live stream: its qualities, the stitched playlists, and the backup in use.
 */
final class StreamSession {
    final String channel;
    /** Usher master playlist URL the app requested; backups reuse its parameters. */
    final String masterUrl;
    final MasterPlaylist master;

    final Map<String, PlaylistStitcher> stitchers = new HashMap<>();
    /** Backup master playlist per player type, reused until it turns out stale. */
    final Map<String, String> backupMasters = new HashMap<>();
    /** Backup chosen per quality for the current ad break. */
    final Map<String, BackupStreamFinder.Result> backupResults = new HashMap<>();
    final Map<String, Long> failedPlayerTypes = new HashMap<>();

    /** The playlist carries ad markers (with or without ad segments). */
    boolean inAdBreak;
    /** Ad segments are being replaced or cut out. */
    boolean swappingAds;
    long lastSeen;
    /** The quality (master playlist URL) polled most recently. */
    String lastVariantUrl;
    /** Playlist URLs Twitch moved a quality to, mapped to that quality's master playlist URL. */
    final Map<String, String> aliases = new HashMap<>();

    StreamSession(String channel, String masterUrl, MasterPlaylist master, long now) {
        this.channel = channel;
        this.masterUrl = masterUrl;
        this.master = master;
        this.lastSeen = now;
    }

    /** The master playlist URL of the quality {@code url} belongs to. */
    String variantUrlFor(String url) {
        String alias = aliases.get(UrlKey.of(url));
        return alias != null ? alias : url;
    }

    PlaylistStitcher stitcherFor(String url) {
        // A moved playlist continues the same stitched stream.
        String key = UrlKey.of(variantUrlFor(url));
        PlaylistStitcher stitcher = stitchers.get(key);
        if (stitcher == null) {
            stitcher = new PlaylistStitcher();
            stitchers.put(key, stitcher);
        }
        return stitcher;
    }

    void endAdSwap() {
        swappingAds = false;
        backupResults.clear();
        failedPlayerTypes.clear();
    }
}
