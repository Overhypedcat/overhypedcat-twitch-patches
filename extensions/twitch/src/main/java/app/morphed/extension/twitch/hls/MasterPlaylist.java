package app.morphed.extension.twitch.hls;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The usher playlist that lists one media playlist per quality.
 */
public final class MasterPlaylist {
    public static final class Variant {
        public final String url;
        /** For example {@code 1920x1080}, or null for audio only. */
        public final String resolution;
        public final String frameRate;
        public final String codecs;

        Variant(String url, String resolution, String frameRate, String codecs) {
            this.url = url;
            this.resolution = resolution;
            this.frameRate = frameRate;
            this.codecs = codecs;
        }

        long area() {
            if (resolution == null) return -1;
            int x = resolution.indexOf('x');
            if (x < 0) return -1;
            try {
                return Long.parseLong(resolution.substring(0, x)) * Long.parseLong(resolution.substring(x + 1));
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        /** Height in pixels, or -1 for audio only. */
        public int height() {
            if (resolution == null) return -1;
            int x = resolution.indexOf('x');
            if (x < 0) return -1;
            try {
                return Integer.parseInt(resolution.substring(x + 1));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }

    public final List<Variant> variants;

    private MasterPlaylist(List<Variant> variants) {
        this.variants = Collections.unmodifiableList(variants);
    }

    public static MasterPlaylist parse(String text) {
        String[] lines = Lines.of(text);
        List<Variant> variants = new ArrayList<>();
        for (int i = 0; i < lines.length - 1; i++) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue;
            String url = lines[i + 1].trim();
            if (url.isEmpty() || url.startsWith("#")) continue;

            Map<String, String> attributes = Attributes.parse(lines[i]);
            variants.add(new Variant(url, attributes.get("RESOLUTION"), attributes.get("FRAME-RATE"), attributes.get("CODECS")));
        }
        return new MasterPlaylist(variants);
    }

    public Variant byUrl(String url) {
        String key = UrlKey.of(url);
        for (Variant variant : variants) {
            if (UrlKey.of(variant.url).equals(key)) return variant;
        }
        return null;
    }

    /**
     * The variant that best matches the quality the player is on: same resolution and frame rate,
     * then same resolution, then the closest pixel count.
     */
    public Variant closestTo(Variant target) {
        if (target.resolution == null) {
            for (Variant variant : variants) {
                if (variant.resolution == null) return variant;
            }
            return null;
        }

        Variant sameResolution = null;
        Variant closest = null;
        long closestDifference = Long.MAX_VALUE;
        long targetArea = target.area();
        for (Variant variant : variants) {
            if (variant.resolution == null) continue;
            if (variant.resolution.equals(target.resolution)) {
                if (equalFrameRate(variant.frameRate, target.frameRate)) return variant;
                if (sameResolution == null) sameResolution = variant;
            }
            long difference = Math.abs(variant.area() - targetArea);
            if (difference < closestDifference) {
                closestDifference = difference;
                closest = variant;
            }
        }
        return sameResolution != null ? sameResolution : closest;
    }

    /** The highest video height in this playlist, or -1 when it only has audio. */
    public int bestHeight() {
        int best = -1;
        for (Variant variant : variants) {
            best = Math.max(best, variant.height());
        }
        return best;
    }

    /**
     * Variants to try as a backup for a player on {@code reference}, best first.
     *
     * <p>{@code desiredHeight} is the quality to look for and {@code minHeight} the lowest one that
     * is still acceptable; both come from the stream's own master playlist, not only from the
     * quality the player happens to be on. That way a player left on Auto at 360p still gets a 720p
     * backup when the stream offers one, and a 1080p player falls back to 720p instead of 360p.
     */
    public List<Variant> backupCandidates(Variant reference, int desiredHeight, int minHeight) {
        if (reference.resolution == null) {
            Variant audio = closestTo(reference);
            return audio == null ? Collections.emptyList() : Collections.singletonList(audio);
        }
        List<Variant> candidates = new ArrayList<>();
        for (Variant variant : variants) {
            if (variant.height() == desiredHeight) candidates.add(variant);
        }
        candidates.sort((a, b) -> compareFrameRate(a, b, reference.frameRate));
        if (desiredHeight > 720) {
            List<Variant> at720 = new ArrayList<>();
            for (Variant variant : variants) {
                if (variant.height() == 720) at720.add(variant);
            }
            at720.sort((a, b) -> compareFrameRate(a, b, reference.frameRate));
            candidates.addAll(at720);
        }
        if (candidates.isEmpty()) {
            Variant closest = closestAtLeast(minHeight, desiredHeight);
            if (closest != null) candidates.add(closest);
        }
        List<Variant> usable = new ArrayList<>();
        for (Variant variant : candidates) {
            if (variant.height() >= minHeight && !usable.contains(variant)) usable.add(variant);
        }
        return usable.size() > 3 ? new ArrayList<>(usable.subList(0, 3)) : usable;
    }

    private static int compareFrameRate(Variant a, Variant b, String targetFrameRate) {
        boolean aExact = equalFrameRate(a.frameRate, targetFrameRate);
        boolean bExact = equalFrameRate(b.frameRate, targetFrameRate);
        if (aExact != bExact) return aExact ? -1 : 1;
        return Double.compare(frameRate(b.frameRate), frameRate(a.frameRate));
    }

    private static double frameRate(String value) {
        if (value == null) return 0;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The variant closest in height to {@code desiredHeight}, never below {@code minHeight}, or null. */
    private Variant closestAtLeast(int minHeight, int desiredHeight) {
        Variant closest = null;
        long closestDifference = Long.MAX_VALUE;
        for (Variant variant : variants) {
            int height = variant.height();
            if (height < minHeight) continue;
            long difference = Math.abs((long) height - desiredHeight);
            if (difference < closestDifference
                    || (difference == closestDifference && closest != null && height < closest.height())) {
                closestDifference = difference;
                closest = variant;
            }
        }
        return closest;
    }

    private static boolean equalFrameRate(String a, String b) {
        if (a == null || b == null) return a == null && b == null;
        try {
            return Math.round(Double.parseDouble(a)) == Math.round(Double.parseDouble(b));
        } catch (NumberFormatException e) {
            return a.equals(b);
        }
    }
}
