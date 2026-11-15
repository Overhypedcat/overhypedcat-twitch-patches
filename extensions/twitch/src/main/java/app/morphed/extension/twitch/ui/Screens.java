package app.morphed.extension.twitch.ui;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;

import java.lang.ref.WeakReference;

import app.morphed.extension.twitch.App;
import app.morphed.extension.twitch.patches.DebugLog;
import app.morphed.extension.twitch.settings.PlayerOptionsSection;

/**
 * Follows the app's screens: which one is in front (for the patches' dialogs), the live log window on each, and
 * the player's Options sheet, which gets the patches' switches when it opens.
 */
public final class Screens implements Application.ActivityLifecycleCallbacks {
    private static final long SCAN_GAP_MS = 250;
    private static WeakReference<Activity> resumed = new WeakReference<>(null);
    private static WeakReference<Activity> settings = new WeakReference<>(null);
    private static boolean installed;

    private Screens() {
    }

    /** Injected at the start of the app's Application.onCreate. */
    public static void install(Application application) {
        if (installed || application == null) return;
        installed = true;
        try {
            application.registerActivityLifecycleCallbacks(new Screens());
            DebugLog.resumeIfEnabled();
        } catch (Throwable t) {
            Log.e(App.TAG, "Following the screens failed", t);
        }
    }

    public static Activity current() {
        return resumed.get();
    }

    /** The Settings screen with the patches' rows composed on it (noted while they're built). */
    public static void noteSettings() {
        Activity activity = resumed.get();
        if (activity != null) settings = new WeakReference<>(activity);
    }

    /** The rows' texts are read when Settings is built: rebuilt so they show what changed. */
    public static void refreshSettingsRows() {
        Activity activity = settings.get();
        if (activity != null && !activity.isFinishing() && activity == resumed.get()) activity.recreate();
    }

    @Override
    public void onActivityResumed(Activity activity) {
        resumed = new WeakReference<>(activity);
        try {
            if (DebugLog.isOn()) LogWindow.show(activity);
            watch(activity);
        } catch (Throwable t) {
            Log.e(App.TAG, "Preparing the screen failed", t);
        }
    }

    @Override
    public void onActivityPaused(Activity activity) {
        if (resumed.get() == activity) LogWindow.hide();
        // Left Settings: its rows aren't on screen, nothing to rebuild (the player's screen must never be).
        if (settings.get() == activity) settings = new WeakReference<>(null);
    }

    private static void watch(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        Object tag = decor.getTag(TAG_KEY);
        if (tag != null) return;
        long[] last = {0};
        boolean[] queued = {false};
        ViewTreeObserver.OnGlobalLayoutListener listener = () -> {
            if (queued[0]) return;
            long wait = Math.max(0, SCAN_GAP_MS - (SystemClock.uptimeMillis() - last[0]));
            queued[0] = true;
            decor.postDelayed(() -> {
                queued[0] = false;
                last[0] = SystemClock.uptimeMillis();
                // The log is too small to read in picture in picture.
                if (android.os.Build.VERSION.SDK_INT >= 24 && activity.isInPictureInPictureMode()) LogWindow.hide();
                else if (DebugLog.isOn() && activity == resumed.get()) LogWindow.show(activity);
                try {
                    PlayerOptionsSection.scan(activity, decor);
                } catch (Throwable t) {
                    PlayerOptionsSection.failed(t);
                }
            }, wait);
        };
        decor.getViewTreeObserver().addOnGlobalLayoutListener(listener);
        decor.setTag(TAG_KEY, listener);
    }

    /** A key for our tag on the window's root view (any id-like constant outside the app's own ids works). */
    private static final int TAG_KEY = 0x7e000101;

    @Override
    public void onActivityCreated(Activity activity, Bundle saved) {
    }

    @Override
    public void onActivityStarted(Activity activity) {
    }

    @Override
    public void onActivityStopped(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
    }
}
