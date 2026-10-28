package app.morphed.extension.twitch.hls;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A live media playlist split into header, segments and trailing tags.
 */
public final class MediaPlaylist {
    public static final long UNKNOWN_TIME = Long.MIN_VALUE;

    public static final class Segment {
        /** Tag lines that belong to this segment, in order, including EXTINF. */
        public final List<String> tags;
        public final String uri;
        public final long sequence;
        public final boolean live;
        public final boolean discontinuity;
        /** EXT-X-PROGRAM-DATE-TIME in epoch milliseconds, or {@link #UNKNOWN_TIME}. */
        public final long programDateTime;

        private final boolean ad;

        Segment(List<String> tags, String uri, long sequence, boolean liveTitlesInUse) {
            this.tags = Collections.unmodifiableList(tags);
            this.uri = uri;
            this.sequence = sequence;

            boolean live = false;
            boolean discontinuity = false;
            long programDateTime = UNKNOWN_TIME;
            for (String tag : tags) {
                if (tag.startsWith("#EXTINF")) {
                    // Twitch titles content segments "live"; stitched ads carry an ad title instead.
                    live = tag.contains(",live");
                } else if (tag.startsWith("#EXT-X-DISCONTINUITY") && !tag.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE")) {
                    discontinuity = true;
                } else if (tag.startsWith("#EXT-X-PROGRAM-DATE-TIME:")) {
                    programDateTime = parseTime(tag.substring("#EXT-X-PROGRAM-DATE-TIME:".length()));
                }
            }
            this.live = live;
            this.discontinuity = discontinuity;
            this.programDateTime = programDateTime;
            // Only trust the title when the playlist uses "live" titles or carries ad markers, so a
            // format change at Twitch cannot turn every segment into an "ad" and freeze playback.
            this.ad = (liveTitlesInUse && !live) || AdMarkers.isAdSegmentUrl(uri);
        }

        public boolean isAd() {
            return ad;
        }
    }

    /** Lines before the first segment, without MEDIA-SEQUENCE and DISCONTINUITY-SEQUENCE. */
    public final List<String> header;
    public final List<Segment> segments;
    /** Tags after the last segment, such as prefetch hints and ENDLIST. */
    public final List<String> trailer;
    public final long mediaSequence;
    public final long discontinuitySequence;
    public final boolean hasAdTags;

    private MediaPlaylist(List<String> header, List<Segment> segments, List<String> trailer,
                          long mediaSequence, long discontinuitySequence, boolean hasAdTags) {
        this.header = Collections.unmodifiableList(header);
        this.segments = Collections.unmodifiableList(segments);
        this.trailer = Collections.unmodifiableList(trailer);
        this.mediaSequence = mediaSequence;
        this.discontinuitySequence = discontinuitySequence;
        this.hasAdTags = hasAdTags;
    }

    public boolean hasAdSegments() {
        for (Segment segment : segments) {
            if (segment.isAd()) return true;
        }
        return false;
    }

    public boolean hasAds() {
        return hasAdTags || hasAdSegments();
    }

    public static MediaPlaylist parse(String text) {
        List<String> header = new ArrayList<>();
        List<List<String>> segmentTags = new ArrayList<>();
        List<String> segmentUris = new ArrayList<>();
        List<String> trailer = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        long mediaSequence = 0;
        long discontinuitySequence = 0;
        boolean inSegments = false;

        for (String rawLine : text.split("\r?\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                mediaSequence = parseLong(line.substring("#EXT-X-MEDIA-SEQUENCE:".length()), 0);
            } else if (line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE:")) {
                discontinuitySequence = parseLong(line.substring("#EXT-X-DISCONTINUITY-SEQUENCE:".length()), 0);
            } else if (line.startsWith("#")) {
                if (isTrailerTag(line)) {
                    trailer.add(line);
                } else if (inSegments || isSegmentTag(line)) {
                    inSegments = true;
                    pending.add(line);
                } else {
                    header.add(line);
                }
            } else {
                segmentTags.add(new ArrayList<>(pending));
                segmentUris.add(line);
                pending.clear();
                inSegments = true;
            }
        }
        // Tags left after the last URI (for example a DATERANGE for the next segment) stay with the trailer.
        trailer.addAll(0, pending);

        boolean hasAdTags = AdMarkers.hasAdTags(text);
        boolean liveTitlesInUse = hasAdTags || text.contains(",live");
        List<Segment> segments = new ArrayList<>(segmentUris.size());
        for (int i = 0; i < segmentUris.size(); i++) {
            segments.add(new Segment(segmentTags.get(i), segmentUris.get(i), mediaSequence + i, liveTitlesInUse));
        }

        return new MediaPlaylist(header, segments, trailer, mediaSequence, discontinuitySequence, hasAdTags);
    }

    private static boolean isSegmentTag(String line) {
        return line.startsWith("#EXTINF")
                || line.startsWith("#EXT-X-PROGRAM-DATE-TIME")
                || line.startsWith("#EXT-X-DISCONTINUITY")
                || line.startsWith("#EXT-X-DATERANGE")
                || line.startsWith("#EXT-X-BYTERANGE")
                || line.startsWith("#EXT-X-KEY")
                || line.startsWith("#EXT-X-MAP")
                || line.startsWith("#EXT-X-CUE")
                || line.startsWith("#EXT-X-SCTE35")
                || line.startsWith("#EXT-X-PART:")
                || line.startsWith("#EXT-X-GAP")
                || line.startsWith("#EXT-X-BITRATE");
    }

    static boolean isTrailerTag(String line) {
        return line.startsWith("#EXT-X-TWITCH-PREFETCH:")
                || line.startsWith("#EXT-X-PRELOAD-HINT:")
                || line.startsWith("#EXT-X-RENDITION-REPORT")
                || line.startsWith("#EXT-X-ENDLIST");
    }

    static boolean isPrefetchTag(String line) {
        return line.startsWith("#EXT-X-TWITCH-PREFETCH:") || line.startsWith("#EXT-X-PRELOAD-HINT:");
    }

    private static long parseTime(String value) {
        try {
            return OffsetDateTime.parse(value.trim()).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return UNKNOWN_TIME;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
