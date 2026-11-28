package app.morphed.extension.twitch.adblock;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What the ad blocker is doing for the stream being watched, and the latest problem it hit with it, for the line
 * under its switch in the player's Options (as song removal's). A new stream starts it afresh: an old stream's
 * problem isn't this one's.
 */
public final class AdBlockStatus {
    /** One state; activity or problem may be null (nothing to say). */
    public static final class Status {
        /** What it's doing now, in a few words. */
        public final String activity;
        /** The latest problem's few words (its heading and code), or null. */
        public final String problem;
        /** The problem's whole reason, to report, or null. */
        public final String problemDetail;

        Status(String activity, String problem, String problemDetail) {
            this.activity = activity;
            this.problem = problem;
            this.problemDetail = problemDetail;
        }

        /** The line's words: the problem when there is one (it matters more), else what it's doing. */
        public String line() {
            return problem != null ? problem : activity;
        }
    }

    public interface Listener {
        /** Called on the thread that changed it: a listener showing it moves to the main thread itself. */
        void changed(Status status);
    }

    static final String WATCHING = "No ads so far";
    static final String CUTTING = "Cutting an ad out (no ad-free stream)";
    static final String CLIENT_AD = "Stopped an ad the app plays itself";
    static final String BREAK_OVER = "Ad break over";

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile Status status = new Status(null, null, null);

    private AdBlockStatus() {
    }

    public static Status status() {
        return status;
    }

    public static void addListener(Listener listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    /** A stream to watch was opened: nothing blocked in it yet, no problem of its own yet. */
    public static void watching() {
        publish(new Status(WATCHING, null, null));
    }

    /** An ad in the stream is being blocked: replaced by an ad-free stream at this quality, or cut out (null). */
    public static void blocking(String quality) {
        String activity = quality == null ? CUTTING : "Blocking an ad, " + quality + " meanwhile";
        Status now = status;
        if (activity.equals(now.activity)) return; // (every poll of the break says it again)
        publish(new Status(activity, now.problem, now.problemDetail));
    }

    public static void breakOver() {
        Status now = status;
        publish(new Status(BREAK_OVER, now.problem, now.problemDetail));
    }

    /** An ad the app plays itself was stopped. */
    public static void clientAdBlocked() {
        Status now = status;
        publish(new Status(CLIENT_AD, now.problem, now.problemDetail));
    }

    /** A problem: shown until the next stream (or a newer problem). */
    public static void problem(Problem problem, String detail) {
        String words = problem.heading + " (" + problem.code + ")";
        String whole = problem.description + (detail == null || detail.isEmpty() ? "" : ": " + detail);
        publish(new Status(status.activity, words, whole));
    }

    /** Forgets everything; for tests. */
    static void reset() {
        status = new Status(null, null, null);
    }

    private static void publish(Status next) {
        status = next;
        for (Listener listener : LISTENERS) {
            try {
                listener.changed(next);
            } catch (Throwable ignored) {
                // (a listener's fault must not reach the playlist being handled; it shows its own failure)
            }
        }
    }
}
