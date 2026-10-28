package app.morphed.extension.twitch.hls;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;

import app.morphed.extension.twitch.Playlists;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AdMarkersTest {
    @Test
    public void detectsStitchedAdDateRange() {
        assertTrue(AdMarkers.hasAdTags(Playlists.AD_DATERANGE));
    }

    @Test
    public void detectsCueOut() {
        assertTrue(AdMarkers.hasAdTags("#EXT-X-CUE-OUT:30"));
    }

    @Test
    public void ignoresSessionMetadata() {
        String line = "#EXT-X-DATERANGE:ID=\"source-1\",CLASS=\"twitch-stream-source\",START-DATE=\"2026-10-28T12:00:00Z\"";
        assertFalse(AdMarkers.hasAdTags(line));
        assertFalse(AdMarkers.isAdTagLine(line));
    }

    @Test
    public void adTagLinesIncludeCueInAndTrackingAttributes() {
        assertTrue(AdMarkers.isAdTagLine("#EXT-X-CUE-IN"));
        assertTrue(AdMarkers.isAdTagLine("#EXT-X-DATERANGE:ID=\"x\",X-TV-TWITCH-AD-URL=\"https://ads\""));
        assertFalse(AdMarkers.isAdTagLine("https://edge.example/stitched-ad/1.ts"));
    }

    @Test
    public void suspiciousMarkersFindNewAdClassesAndTags() {
        String text = "#EXTM3U\n"
                + "#EXT-X-DATERANGE:ID=\"a\",CLASS=\"twitch-video-ad\",START-DATE=\"2026-10-28T12:00:00Z\"\n"
                + "#EXT-X-SPLICE-POINT:1\n"
                + "#EXT-X-DATERANGE:ID=\"b\",CLASS=\"twitch-stream-source\",START-DATE=\"2026-10-28T12:00:00Z\"\n";

        assertEquals(new LinkedHashSet<>(Arrays.asList("CLASS=\"twitch-video-ad\"", "#EXT-X-SPLICE-POINT")),
                AdMarkers.suspiciousMarkers(text));
    }

    @Test
    public void ordinaryPlaylistHasNoSuspiciousMarkers() {
        assertTrue(AdMarkers.suspiciousMarkers(Playlists.media("n", 100, "llll", false)
                + "#EXT-X-PRELOAD-HINT:TYPE=PART,URI=\"x\"\n").isEmpty());
    }

    @Test
    public void recognisesAdSegmentUrls() {
        assertTrue(AdMarkers.isAdSegmentUrl("https://edge.example/adsquared/1.ts"));
        assertFalse(AdMarkers.isAdSegmentUrl("https://edge.example/live/1.ts"));
    }
}
