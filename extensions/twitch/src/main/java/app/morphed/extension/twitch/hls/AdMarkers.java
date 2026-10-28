package app.morphed.extension.twitch.hls;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ad markers Twitch places in HLS playlists. Kept in sync with vaft's AdSignifiers.
 */
public final class AdMarkers {
    private static final String[] SIGNIFIERS = {
            "stitched-ad",
            "EXT-X-CUE-OUT",
            "twitch-stitched",
            "EXT-X-DATERANGE:CLASS=\"twitch-maf-ad\"",
            "EXT-X-DATERANGE:CLASS=\"twitch-trigger\"",
    };

    private static final String[] AD_SEGMENT_URL_PATTERNS = {"/adsquared/", "/_404/", "/processing"};

    /** DATERANGE classes Twitch uses for stream metadata, not ads (from vaft's field observations). */
    private static final Set<String> NON_AD_CLASSES = new HashSet<>(Arrays.asList(
            "twitch-session", "twitch-stream-source", "twitch-assignment"));
    private static final Set<String> AD_WORDS = new HashSet<>(Arrays.asList(
            "ad", "ads", "advert", "stitched", "maf", "break", "midroll", "preroll", "sponsor", "cue",
            "scte35", "splice", "commercial", "quartile", "trigger"));
    private static final Pattern DATERANGE_CLASS = Pattern.compile("CLASS=\"([^\"]+)\"");

    private AdMarkers() {
    }

    public static boolean hasAdTags(String text) {
        for (String signifier : SIGNIFIERS) {
            if (text.contains(signifier)) return true;
        }
        return false;
    }

    /**
     * A tag line that tells the app an ad is playing. Dropped from rewritten playlists so the app
     * does not start its own (client side) ad.
     */
    public static boolean isAdTagLine(String line) {
        if (!line.startsWith("#")) return false;
        return hasAdTags(line) || line.startsWith("#EXT-X-CUE-IN") || line.contains("X-TV-TWITCH-AD");
    }

    /**
     * Tags and DATERANGE classes that look ad related. When a playlist has these but no known ad
     * marker, Twitch has most likely changed how ads are marked.
     */
    public static Set<String> suspiciousMarkers(String text) {
        Set<String> markers = new LinkedHashSet<>();
        for (String line : text.split("\r?\n")) {
            if (!line.startsWith("#EXT")) continue;
            int colon = line.indexOf(':');
            String tag = colon >= 0 ? line.substring(0, colon) : line;
            if (hasAdWord(tag.substring(1))) markers.add(tag);

            if (tag.equals("#EXT-X-DATERANGE")) {
                Matcher matcher = DATERANGE_CLASS.matcher(line);
                if (matcher.find()) {
                    String dateRangeClass = matcher.group(1);
                    if (!NON_AD_CLASSES.contains(dateRangeClass) && hasAdWord(dateRangeClass)) {
                        markers.add("CLASS=\"" + dateRangeClass + "\"");
                    }
                }
            }
            if (line.contains("X-TV-TWITCH-AD")) markers.add("X-TV-TWITCH-AD attribute");
        }
        return markers;
    }

    private static boolean hasAdWord(String name) {
        for (String word : name.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (AD_WORDS.contains(word)) return true;
        }
        return false;
    }

    public static boolean isAdSegmentUrl(String url) {
        for (String pattern : AD_SEGMENT_URL_PATTERNS) {
            if (url.contains(pattern)) return true;
        }
        return false;
    }
}
