package app.morphed.extension.twitch.hls;

import org.junit.Test;

import app.morphed.extension.twitch.Playlists;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MediaPlaylistTest {
    @Test
    public void parsesLivePlaylist() {
        MediaPlaylist playlist = MediaPlaylist.parse(Playlists.media("n", 100, "lll", false));

        assertEquals(100, playlist.mediaSequence);
        assertEquals(3, playlist.segments.size());
        assertEquals(102, playlist.segments.get(2).sequence);
        assertEquals(Playlists.segment("n", 101), playlist.segments.get(1).uri);
        assertEquals(Playlists.time(101), playlist.segments.get(1).programDateTime);
        assertFalse(playlist.hasAds());
        assertTrue(playlist.trailer.get(0).startsWith("#EXT-X-TWITCH-PREFETCH:"));
        assertTrue(playlist.header.contains("#EXT-X-TARGETDURATION:6"));
    }

    @Test
    public void marksStitchedAdSegments() {
        MediaPlaylist playlist = MediaPlaylist.parse(Playlists.media("n", 100, "llaa", true));

        assertTrue(playlist.hasAdTags);
        assertTrue(playlist.hasAdSegments());
        assertFalse(playlist.segments.get(1).isAd());
        assertTrue(playlist.segments.get(2).isAd());
        assertTrue(playlist.segments.get(2).discontinuity);
    }

    @Test
    public void markersWithoutAdSegments() {
        MediaPlaylist playlist = MediaPlaylist.parse(Playlists.media("n", 100, "lll", true));

        assertTrue(playlist.hasAds());
        assertFalse(playlist.hasAdSegments());
    }

    @Test
    public void untitledSegmentsAreNotAdsWhenNoSegmentIsTitledLive() {
        MediaPlaylist playlist = MediaPlaylist.parse("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:5\n#EXTINF:2.000,\nhttps://e/5.ts\n");

        assertFalse(playlist.segments.get(0).isAd());
    }

    @Test
    public void playlistOfOnlyAdsIsAllAds() {
        MediaPlaylist playlist = MediaPlaylist.parse(Playlists.media("n", 100, "aa", true));

        assertTrue(playlist.segments.get(0).isAd());
        assertTrue(playlist.segments.get(1).isAd());
    }

    @Test
    public void adSegmentUrlPatternWins() {
        MediaPlaylist playlist = MediaPlaylist.parse("#EXTM3U\n#EXTINF:2.000,live\nhttps://e/adsquared/1.ts\n");

        assertTrue(playlist.segments.get(0).isAd());
    }
}
