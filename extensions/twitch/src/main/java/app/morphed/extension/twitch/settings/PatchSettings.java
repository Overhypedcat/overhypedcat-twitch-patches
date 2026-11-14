package app.morphed.extension.twitch.settings;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.concurrent.CopyOnWriteArrayList;

import app.morphed.extension.twitch.App;

/**
 * The patches' switches: the ad blocker, song removal and the quality unlock (the video player's Options), kept
 * across launches. Read on every playlist and audio buffer, so the values are cached in memory.
 */
public final class PatchSettings {
    public static final String PREFERENCES = "twitch_morphed";
    public static final String AD_BLOCK = "ad_block";
    public static final String SONG_REMOVAL = "song_removal";
    public static final String QUALITY_UNLOCK = "quality_unlock";

    public interface Listener {
        void changed(String key, boolean on);
    }

    private static volatile boolean loaded;
    private static volatile boolean adBlock = true;
    private static volatile boolean songRemoval = false; // off until switched on (as Mor's filter)
    private static volatile boolean qualityUnlock = false; // on once a VPN server is added
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private PatchSettings() {
    }

    private static void load() {
        if (loaded) return;
        synchronized (PatchSettings.class) {
            if (loaded) return;
            Context context = App.application();
            if (context == null) return; // too early: defaults until the app is there
            SharedPreferences preferences = preferences(context);
            adBlock = preferences.getBoolean(AD_BLOCK, true);
            songRemoval = preferences.getBoolean(SONG_REMOVAL, false);
            qualityUnlock = preferences.getBoolean(QUALITY_UNLOCK, false);
            loaded = true;
        }
    }

    public static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public static boolean adBlock() {
        load();
        return adBlock;
    }

    public static boolean songRemoval() {
        load();
        return songRemoval;
    }

    public static boolean qualityUnlock() {
        load();
        return qualityUnlock;
    }

    public static boolean get(String key) {
        switch (key) {
            case AD_BLOCK: return adBlock();
            case SONG_REMOVAL: return songRemoval();
            case QUALITY_UNLOCK: return qualityUnlock();
            default: throw new IllegalArgumentException(key);
        }
    }

    public static void set(String key, boolean on) {
        load();
        switch (key) {
            case AD_BLOCK: adBlock = on; break;
            case SONG_REMOVAL: songRemoval = on; break;
            case QUALITY_UNLOCK: qualityUnlock = on; break;
            default: throw new IllegalArgumentException(key);
        }
        Context context = App.application();
        if (context != null) preferences(context).edit().putBoolean(key, on).apply();
        for (Listener listener : LISTENERS) {
            try {
                listener.changed(key, on);
            } catch (Throwable t) { // one listener's failure mustn't stop the switch or crash the tap
                android.util.Log.e(App.TAG, "A settings listener failed", t);
            }
        }
    }

    public static void addListener(Listener listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    /** For tests: values without an app. */
    static void setForTest(boolean block, boolean songs, boolean unlock) {
        adBlock = block;
        songRemoval = songs;
        qualityUnlock = unlock;
        loaded = true;
    }
}
