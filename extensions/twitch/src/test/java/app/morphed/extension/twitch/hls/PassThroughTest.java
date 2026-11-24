package app.morphed.extension.twitch.hls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import app.morphed.extension.twitch.Playlists;

/** Twitch's own playlist handed on: its numbers shifted after a break, its prefetch hints held back only ahead of one. */
public class PassThroughTest {
    @Test
    public void numbersAreShiftedNothingElse() {
        String text = "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:100\n#EXT-X-DISCONTINUITY-SEQUENCE:2\n#EXTINF:2.000,live\nhttps://e/1.ts\n"
                + "#EXT-X-PART:DURATION=0.5,URI=\"https://e/1.0.ts\"\n#EXT-X-RENDITION-REPORT:URI=\"x.m3u8\",LAST-MSN=104\n";
        String out = PlaylistStitcher.shifted(text, -3, 1);
        assertEquals("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:97\n#EXT-X-DISCONTINUITY-SEQUENCE:3\n#EXTINF:2.000,live\nhttps://e/1.ts\n"
                + "#EXT-X-PART:DURATION=0.5,URI=\"https://e/1.0.ts\"\n#EXT-X-RENDITION-REPORT:URI=\"x.m3u8\",LAST-MSN=101\n", out);
        assertTrue("no discontinuity sequence given: one is added after the media sequence",
                PlaylistStitcher.shifted("#EXT-X-MEDIA-SEQUENCE:5\nx\n", 0, 2).startsWith("#EXT-X-MEDIA-SEQUENCE:5\n#EXT-X-DISCONTINUITY-SEQUENCE:2\n"));
        assertEquals("nothing to shift", text, PlaylistStitcher.shifted(text, 0, 0));
    }

    @Test
    public void aBreakIsAheadOnlyWhenItStartsAfterTheNewestSegment() {
        long newest = Playlists.time(104);
        String at = Playlists.AD_DATERANGE.replace("2026-10-28T12:00:00.000Z", "2026-10-28T12:03:28.000Z"); // = 104
        String after = Playlists.AD_DATERANGE.replace("2026-10-28T12:00:00.000Z", "2026-10-28T12:03:29.500Z");
        assertFalse("begun: its ad segments say so themselves", AdMarkers.breakAhead(at, newest));
        assertTrue(AdMarkers.breakAhead(after, newest));
        assertTrue("a past marker and a future one", AdMarkers.breakAhead(Playlists.AD_DATERANGE + "\n" + after, newest));
        assertFalse("no start date", AdMarkers.breakAhead("#EXT-X-DATERANGE:ID=\"a\",CLASS=\"twitch-stitched-ad\"", newest));
        assertFalse("no time to compare with", AdMarkers.breakAhead(after, MediaPlaylist.UNKNOWN_TIME));
    }

    @Test
    public void markersGoButPartsAndHintsStay() {
        String text = "#EXTM3U\n" + Playlists.AD_DATERANGE + "\n#EXTINF:2.000,live\nhttps://e/1.ts\n"
                + "#EXT-X-PART:DURATION=0.5,URI=\"https://e/2.0.ts\"\n#EXT-X-TWITCH-PREFETCH:https://e/2.ts\n";
        String out = AdMarkers.withoutAdTags(text, false);
        assertFalse(out.contains("stitched-ad"));
        assertTrue(out.contains("#EXT-X-PART:") && out.contains("#EXT-X-TWITCH-PREFETCH:"));
        assertFalse(AdMarkers.withoutAdTags(text, true).contains("#EXT-X-TWITCH-PREFETCH:"));
    }
}
