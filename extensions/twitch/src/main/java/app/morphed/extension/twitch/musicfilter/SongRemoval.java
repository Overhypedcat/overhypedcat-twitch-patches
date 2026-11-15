package app.morphed.extension.twitch.musicfilter;

import android.content.Context;
import android.media.MediaFormat;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import app.morphed.extension.twitch.App;
import app.morphed.extension.twitch.net.DirectWeb;
import app.morphed.extension.twitch.patches.DebugLog;
import app.morphed.extension.twitch.settings.PatchSettings;

/**
 * Song removal: takes the songs a streamer plays out of the stream's sound, the way Mor does.
 * <p>
 * The player's audio renderer hands every buffer here right before playing it ({@link #render}); it comes back
 * with the song subtracted. Each renderer runs a fixed delay line (the cancellers' frame), switch on or off, and
 * reports its sound that much earlier, so the picture stays in step and switching never shifts it. One session
 * per stream works out the song and its place (Shazam, YouTube, lining up): it carries on when the player is
 * rebuilt (the ad blocker's reloads, the mini player, picture in picture), so a new player needs no new search.
 * Muted players (feed previews) are never listened to.
 */
public final class SongRemoval {
    static final long LATENCY_US = (Canceller.DELAY + Canceller.HOP) * 1_000_000L / Canceller.RATE;
    private static final int QUEUE = 16384;
    private static final long PROBLEM_TOAST_GAP_MS = 20_000;
    private static final long STALE_MS = 3_000;

    /** What the filter is doing, for the player's Options. */
    public static final class Status {
        public final boolean filtering;
        public final String step;
        public final String problem;
        public final int readyIn;

        Status(boolean filtering, String step, String problem, int readyIn) {
            this.filtering = filtering;
            this.step = step;
            this.problem = problem;
            this.readyIn = readyIn;
        }
    }

    public interface StatusListener {
        void changed(Status status);
    }

    private static final CopyOnWriteArrayList<StatusListener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Map<Object, Renderer> RENDERERS = new WeakHashMap<>();
    private static volatile Session session;
    private static volatile String streamKey = "";
    private static volatile Status status = new Status(false, "Off", null, 0);
    private static Budget budget; // Shazam's, shared by every stream (one connection)
    private static SongLibrary library;
    private static volatile Field formatField, volumeField;
    private static volatile boolean reflectionFailed;
    private static String lastToast;
    private static long lastToastAt;

    static {
        PatchSettings.addListener((key, on) -> {
            if (!PatchSettings.SONG_REMOVAL.equals(key)) return;
            if (!on) stopSession("Off"); // (switched off here, or a stream it's off for is being watched)
        });
    }

    private SongRemoval() {
    }

    /** Which stream plays now (a channel or a past broadcast), from the player's playlist requests. */
    public static void stream(String key) {
        if (key == null || key.equals(streamKey)) return;
        streamKey = key;
        PatchSettings.watching(key); // the switch is per channel
        DebugLog.log("Song removal: stream is " + key);
    }

    public static Status status() {
        return status;
    }

    public static void addListener(StatusListener listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(StatusListener listener) {
        LISTENERS.remove(listener);
    }

    /** The retry button: forget the song and ask Shazam afresh. */
    public static void retry() {
        Session current = session;
        if (current != null) current.tracker.retrySoon();
    }

    // --- the audio renderer

    /** One renderer's delay line and filter, and the buffer its filtered sound is handed back in. */
    private static final class Renderer {
        final int rate, channels;
        final boolean usable;
        final Filter filter;
        float[][] queue = {new float[QUEUE], new float[QUEUE]};
        int queueStart, queueLength;
        float[] left = new float[0], right = new float[0];
        ByteBuffer out; // writable: the decoder's own buffer is read-only
        Session feeding; // the session this one's sound goes to, or null
        boolean failed;
        short[] delay; // after a failure: the plain sound, still LATENCY behind (stays in step with the picture)
        int delayAt;

        Renderer(int rate, int channels) {
            this.rate = rate;
            this.channels = channels;
            usable = rate == Canceller.RATE && (channels == 1 || channels == 2);
            filter = new Filter((mono, length) -> {
                Session to = feeding;
                if (to != null) to.heard.add(mono, length);
            }, SongRemoval::filterBroke);
            queueLength = Canceller.HOP; // a HOP of silence ahead: a whole buffer's worth is always ready
        }

        ByteBuffer output(int size) {
            if (out == null || out.capacity() < size) {
                out = ByteBuffer.allocateDirect(Math.max(size, 8192)).order(ByteOrder.LITTLE_ENDIAN);
            }
            out.clear();
            out.limit(size);
            return out;
        }
    }

    private static void filterBroke(String text) {
        Session current = session;
        if (current != null) current.listener.problem(text);
        else problem(text);
    }

    /**
     * Injected at the start of AudioTrackRenderer.render(buffer, size, presentationTimeUs): the buffer to write in
     * its place, with the song taken out (the decoder's own is read-only). The same size; the time to present it
     * at comes from {@link #presentation}.
     */
    public static ByteBuffer render(Object renderer, ByteBuffer buffer, int size, long presentationUs) {
        if (renderer == null || buffer == null || size <= 0 || reflectionFailed) return buffer;
        Renderer state = null;
        try {
            state = state(renderer);
            if (state == null || !state.usable) return buffer;
            synchronized (state) {
                if (state.failed) return delayed(state, buffer, size);
                return process(renderer, state, buffer, size);
            }
        } catch (Throwable t) {
            Log.e(App.TAG, "Song removal failed", t);
            if (state == null) { // the renderer itself can't be read: off for good, told once
                reflectionFailed = true;
                problem("Song removal can't read the player's sound (Twitch changed it): " + t);
                return buffer;
            }
            synchronized (state) {
                state.failed = true;
            }
            problem("Song removal broke, the sound plays unfiltered: " + t);
            try {
                synchronized (state) {
                    return delayed(state, buffer, size);
                }
            } catch (Throwable again) {
                return buffer;
            }
        }
    }

    /** Injected right after render(): the time the (delayed) sound plays at, so the picture stays in step. */
    public static long presentation(Object renderer, ByteBuffer buffer, int size, long presentationUs) {
        if (renderer == null || reflectionFailed) return presentationUs;
        Renderer state;
        synchronized (RENDERERS) {
            state = RENDERERS.get(renderer);
        }
        // (never before 0: a past broadcast starts there)
        return state != null && state.usable ? Math.max(0, presentationUs - LATENCY_US) : presentationUs;
    }

    private static Renderer state(Object renderer) throws ReflectiveOperationException {
        synchronized (RENDERERS) {
            Renderer state = RENDERERS.get(renderer);
            if (state != null) return state;
            MediaFormat format = format(renderer);
            if (format == null) return null;
            state = new Renderer(format.getInteger(MediaFormat.KEY_SAMPLE_RATE), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
            RENDERERS.put(renderer, state);
            DebugLog.log("Song removal: audio " + state.rate + " Hz, " + state.channels + " channels"
                    + (state.usable ? "" : " (not filtered)"));
            if (!state.usable && PatchSettings.songRemoval()) {
                problem("Song removal works on 48 kHz stereo streams only, this one is " + state.rate + " Hz");
            }
            return state;
        }
    }

    /** Injected at the start of AudioTrackRenderer.flush(): what was queued belongs to the old position. */
    public static void flush(Object renderer) {
        synchronized (RENDERERS) {
            RENDERERS.remove(renderer);
        }
    }

    /** After a failure: the sound as it is, LATENCY behind like the filter's, so the picture stays in step. */
    private static ByteBuffer delayed(Renderer state, ByteBuffer buffer, int size) {
        int latency = (Canceller.DELAY + Canceller.HOP) * state.channels; // exactly the filter's delay
        if (state.delay == null) state.delay = new short[latency];
        ByteBuffer in = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer out = state.output(size);
        int start = buffer.position();
        for (int i = 0; i < size / 2; i++) {
            short sample = in.getShort(start + 2 * i);
            out.putShort(2 * i, state.delay[state.delayAt]);
            state.delay[state.delayAt] = sample;
            state.delayAt = (state.delayAt + 1) % latency;
        }
        return out;
    }

    private static ByteBuffer process(Object renderer, Renderer state, ByteBuffer buffer, int size) {
        boolean on = PatchSettings.songRemoval();
        boolean audible = volume(renderer) > 0.001f;
        // Only the stream opened to watch (not a preview on the home or Following page), while it plays with sound.
        Session current = on && audible && app.morphed.extension.twitch.patches.Watching.active() ? sessionFor(streamKey) : null;
        if (current != state.feeding) {
            if (current != null) {
                current.attached(state);
                state.filter.continueAt(current.heard.total());
            }
            state.feeding = current;
        }
        state.filter.enabled = on && current != null;
        state.filter.wanted = current != null ? current.tracker.playing : null;

        ByteBuffer pcm = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int start = buffer.position();
        int frames = size / (2 * state.channels);
        if (state.left.length < frames) {
            state.left = new float[frames];
            state.right = new float[frames];
        }
        for (int f = 0; f < frames; f++) {
            int at = start + f * 2 * state.channels;
            float l = pcm.getShort(at) / 32768f;
            state.left[f] = l;
            state.right[f] = state.channels > 1 ? pcm.getShort(at + 2) / 32768f : l;
        }
        // Room for this buffer's sound and what's still queued: grown, never dropped.
        int needed = state.queueLength + frames + Canceller.HOP;
        if (needed > state.queue[0].length) {
            int size2 = Integer.highestOneBit(needed) << 1;
            float[][] grown = {new float[size2], new float[size2]};
            for (int i = 0; i < state.queueLength; i++) {
                int slot = (state.queueStart + i) % state.queue[0].length;
                grown[0][i] = state.queue[0][slot];
                grown[1][i] = state.queue[1][slot];
            }
            state.queue = grown;
            state.queueStart = 0;
        }
        int capacity = state.queue[0].length;
        state.filter.push(state.left, state.right, frames, (l, r) -> {
            for (int i = 0; i < l.length; i++) {
                int slot = (state.queueStart + state.queueLength) % capacity;
                state.queue[0][slot] = l[i];
                state.queue[1][slot] = r[i];
                state.queueLength++;
            }
        });
        ByteBuffer out = state.output(size);
        int ready = Math.min(frames, state.queueLength);
        for (int f = 0; f < frames; f++) {
            float l = 0, r = 0;
            if (f < ready) {
                int slot = (state.queueStart + f) % capacity;
                l = state.queue[0][slot];
                r = state.queue[1][slot];
            }
            int at = f * 2 * state.channels;
            if (state.channels > 1) {
                out.putShort(at, toShort(l));
                out.putShort(at + 2, toShort(r));
            } else {
                out.putShort(at, toShort((l + r) / 2));
            }
        }
        state.queueStart = (state.queueStart + ready) % capacity;
        state.queueLength -= ready;
        return out;
    }

    private static short toShort(float value) {
        return (short) Math.max(-32768, Math.min(32767, Math.round(value * 32768f)));
    }

    private static MediaFormat format(Object renderer) throws ReflectiveOperationException {
        if (formatField == null) {
            Field field = renderer.getClass().getDeclaredField("format");
            field.setAccessible(true);
            formatField = field;
        }
        return (MediaFormat) formatField.get(renderer);
    }

    private static float volume(Object renderer) {
        try {
            if (volumeField == null) {
                Field field = renderer.getClass().getDeclaredField("volume");
                field.setAccessible(true);
                volumeField = field;
            }
            return volumeField.getFloat(renderer);
        } catch (ReflectiveOperationException e) {
            // Without the volume, every player counts as heard: still filtered, previews may be listened to.
            return 1f;
        }
    }

    // --- the session that works out the song

    private static final java.util.concurrent.ExecutorService SETUP = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "TwitchMorphed-songs-setup");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile boolean settingUp;
    private static volatile long setupFailedAt;

    /**
     * The session for this stream. Called on the audio thread, so it never waits: the library (files, its index)
     * is made on a thread of its own, and until then the sound only goes through the delay line.
     */
    private static synchronized Session sessionFor(String key) {
        Session current = session;
        if (current != null && current.key.equals(key)) return current;
        if (library == null) {
            // (a setup that failed is tried again after a while, not on every buffer)
            if (!settingUp && System.currentTimeMillis() - setupFailedAt > 30_000) {
                settingUp = true;
                SETUP.execute(SongRemoval::makeLibrary);
            }
            return null;
        }
        if (current != null) current.stop();
        Align.forget(); // the old song's spectrum: memory
        if (budget == null) budget = new Budget(Clock.SYSTEM);
        Session made = new Session(key);
        session = made;
        made.start();
        return made;
    }

    private static void makeLibrary() {
        try {
            Context context = App.application();
            if (context == null) throw new IllegalStateException("no app yet");
            DirectWeb web = new DirectWeb();
            YouTubeClient youtube = new YouTubeClient(web);
            SongLibrary made = new SongLibrary(new File(context.getCacheDir(), "songs"), new SongLibrary.Source() {
                @Override
                public java.util.List<String> search(Song song, boolean music) throws SongProblem {
                    return youtube.search(song, music);
                }

                @Override
                public File download(String videoId, File folder) throws SongProblem {
                    return youtube.download(videoId, folder);
                }
            }, new AndroidDecoder());
            synchronized (SongRemoval.class) {
                library = made;
            }
        } catch (Throwable t) {
            Log.e(App.TAG, "Song removal setup failed", t);
            setupFailedAt = System.currentTimeMillis();
            problem("Song removal couldn't start: " + t);
        } finally {
            settingUp = false;
        }
    }

    private static synchronized void stopSession(String step) {
        Session current = session;
        session = null;
        if (current != null) current.stop();
        Align.forget();
        publish(new Status(false, step, null, 0));
    }

    private static final class Session implements Tracker.Listener {
        final String key;
        final Heard heard = new Heard();
        final Tracker tracker;
        final Tracker.Listener listener = this;
        private volatile boolean running = true;
        private boolean filtering;
        private String step = "Listening for music", problem;
        private int readyIn;

        Session(String key) {
            this.key = key;
            tracker = new Tracker(heard, library, new ShazamClient(new DirectWeb()), Clock.SYSTEM, budget, this);
        }

        void start() {
            DebugLog.log("Song removal: listening to " + key);
            publish(new Status(false, step, null, 0));
            Thread thread = new Thread(() -> {
                // Background priority (as other app work): the lowest would leave it starved for minutes.
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                tracker.tellReady();
                long lastTotal = -1, changedAt = System.currentTimeMillis();
                while (running) {
                    // Nothing new heard (the stream was left, paused or muted): nothing to look at, and Shazam
                    // must not be asked about old sound.
                    long total = heard.total();
                    long now = System.currentTimeMillis();
                    if (total != lastTotal) {
                        lastTotal = total;
                        changedAt = now;
                    }
                    if (now - changedAt < STALE_MS) tracker.look();
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "TwitchMorphed-songs");
            thread.setDaemon(true);
            thread.start();
        }

        void attached(Renderer renderer) {
            DebugLog.log("Song removal: following a new player for " + key);
        }

        void stop() {
            running = false;
            DebugLog.log("Song removal: stopped for " + key);
        }

        private void update() {
            if (running && session == this) publish(new Status(filtering, step, problem, readyIn));
        }

        @Override
        public synchronized void state(boolean filtering) {
            this.filtering = filtering;
            if (filtering) problem = null;
            update();
        }

        @Override
        public synchronized void problem(String text) {
            if (text == null ? problem == null : text.equals(problem)) return;
            problem = text;
            DebugLog.log("Song removal: " + (text == null ? "fine again" : "PROBLEM " + text));
            update();
            if (text != null && running && session == this) SongRemoval.toast(text);
        }

        @Override
        public synchronized void ready(int seconds) {
            readyIn = Math.max(0, seconds);
            update();
        }

        @Override
        public synchronized void step(String text) {
            if (text.equals(step)) return;
            step = text;
            DebugLog.log("Song removal: " + text);
            update();
        }

        @Override
        public void timing(String text) {
            DebugLog.log("Song removal: " + text);
        }
    }

    private static void publish(Status next) {
        status = next;
        App.onMain(() -> {
            for (StatusListener listener : LISTENERS) listener.changed(next);
        });
    }

    /** A problem outside a session (format, a broken renderer): told in the Options and on screen. */
    private static void problem(String text) {
        DebugLog.log("Song removal: PROBLEM " + text);
        Status now = status;
        publish(new Status(now.filtering, now.step, text, now.readyIn));
        toast(text);
    }

    /** Problems are shown on screen, the same one not again within a while. */
    private static void toast(String text) {
        synchronized (SongRemoval.class) {
            long now = System.currentTimeMillis();
            if (text.equals(lastToast) && now - lastToastAt < 10 * 60_000) return;
            if (now - lastToastAt < PROBLEM_TOAST_GAP_MS && lastToast != null) return;
            lastToast = text;
            lastToastAt = now;
        }
        App.onMain(() -> {
            Context context = App.application();
            if (context != null) Toast.makeText(context, "Song removal: " + text, Toast.LENGTH_LONG).show();
        });
    }
}
