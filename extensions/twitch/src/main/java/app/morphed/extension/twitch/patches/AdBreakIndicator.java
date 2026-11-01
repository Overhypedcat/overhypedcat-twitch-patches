package app.morphed.extension.twitch.patches;

import android.app.Activity;
import android.app.Application;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.os.Build;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A small label in the top left corner of the stream's video while an ad is being blocked, in the
 * vertical and the full screen player. Never shown without a stream video on screen.
 */
final class AdBreakIndicator {
    /** Hide the label if no playlist update arrives for this long, for example after leaving the stream. */
    private static final long STALE_MS = 15_000;
    private static final long FLASH_MS = 3_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable hideRunnable = this::hideNow;
    private WeakReference<Activity> resumedActivity = new WeakReference<>(null);
    private WeakReference<View> video = new WeakReference<>(null);
    private boolean callbacksRegistered;
    private TextView label;

    void show(String quality) {
        show(quality == null ? "Blocking ads" : "Blocking ads (" + quality + ")", STALE_MS);
    }

    /** Shows {@code text} briefly, for blocks that are over at once (a refused ad request). */
    void flash(String text) {
        main.post(() -> {
            // An ongoing break keeps its own text.
            if (label != null && !text.contentEquals(label.getText())) return;
            show(text, FLASH_MS);
        });
    }

    private void show(String text, long visibleMs) {
        main.post(() -> {
            registerCallbacks();
            Activity activity = resumedActivity.get();
            if (activity == null) {
                DebugLog.log("Label: no screen in front, not shown");
                return;
            }

            // The video can be in another window than the activity (a full screen or theatre player);
            // the label goes into the window of the video, so the player cannot cover it.
            View surface = video.get();
            if (surface == null || !isOnScreen(surface)) {
                surface = findVideoSurface(rootViews(activity));
                video = new WeakReference<>(surface);
            }
            // The label belongs on the stream's video; without one on screen there is nothing to show it on.
            if (surface == null || !(surface.getRootView() instanceof ViewGroup)) {
                if (label != null) hideNow();
                DebugLog.log("Label not shown (" + text + "): no stream video on screen");
                return;
            }
            ViewGroup host = (ViewGroup) surface.getRootView();

            if (label == null || label.getParent() != host) {
                hideNow();
                label = createLabel(activity);
                host.addView(label, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
                DebugLog.log("Label shown: " + text + " in " + host.getClass().getSimpleName()
                        + " over " + surface.getClass().getSimpleName() + " " + describe(surface));
            }
            if (!text.contentEquals(label.getText())) label.setText(text);
            // Repositioned on every update so it follows the video into fullscreen, landscape or a smaller player.
            place(activity, host, label, surface);
            // bringToFront relayouts the whole window, so only when something else covers the label.
            if (host.getChildAt(host.getChildCount() - 1) != label) label.bringToFront();
            main.removeCallbacks(hideRunnable);
            main.postDelayed(hideRunnable, visibleMs);
        });
    }

    void hide() {
        main.post(this::hideNow);
    }

    private void hideNow() {
        main.removeCallbacks(hideRunnable);
        if (label != null) DebugLog.log("Label hidden");
        if (label != null && label.getParent() instanceof ViewGroup) {
            ((ViewGroup) label.getParent()).removeView(label);
        }
        label = null;
    }

    private static TextView createLabel(Activity activity) {
        TextView view = new TextView(activity);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        view.setTextColor(Color.WHITE);
        view.setSingleLine(true);
        int horizontal = dp(activity, 8);
        int vertical = dp(activity, 3);
        view.setPadding(horizontal, vertical, horizontal, vertical);

        GradientDrawable background = new GradientDrawable();
        background.setColor(0xB3000000);
        background.setCornerRadius(dp(activity, 8));
        view.setBackground(background);
        // No elevation shadow; the label is added last to the window, so it is drawn on top anyway.
        view.setOutlineProvider(null);
        view.setClickable(false);
        view.setFocusable(false);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return view;
    }

    /**
     * Top left corner of the part of the video that is on screen: in full screen the video can be
     * cropped (wider than the screen), and its own corner is then off screen.
     */
    private static void place(Activity activity, ViewGroup decor, TextView label, View video) {
        int margin = dp(activity, 8);
        // Visible rect in the coordinates of the window's root view, which is the label's parent.
        Rect visible = new Rect();
        video.getGlobalVisibleRect(visible);
        int left = Math.max(0, visible.left);
        int top = Math.max(0, visible.top);
        // In fullscreen there is no status bar and the video reaches the screen edge, where the
        // camera cutout can be (on the left in landscape). Keep the label clear of it.
        WindowInsets insets = decor.getRootWindowInsets();
        DisplayCutout cutout = insets != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? insets.getDisplayCutout() : null;
        if (cutout != null) {
            left = Math.max(left, cutout.getSafeInsetLeft());
            top = Math.max(top, cutout.getSafeInsetTop());
        }

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) label.getLayoutParams();
        if (params.leftMargin != left + margin || params.topMargin != top + margin) {
            params.setMargins(left + margin, top + margin, margin, margin);
            label.setLayoutParams(params);
        }
    }

    /**
     * The video surface (SurfaceView or TextureView) with the most of it actually on screen, across
     * all windows. Preloaded preview players sit in pages off screen and still count as "shown" to
     * Android, so the visible area decides, not the size.
     */
    private static View findVideoSurface(List<View> roots) {
        View best = null;
        long bestArea = 0;
        for (View root : roots) {
            View candidate = mostVisibleSurface(root);
            long area = visibleArea(candidate);
            if (area > bestArea) {
                bestArea = area;
                best = candidate;
            }
        }
        return best;
    }

    private static View mostVisibleSurface(View view) {
        if (view == null || !view.isShown()) return null;
        if (view instanceof SurfaceView || view instanceof TextureView) return visibleArea(view) > 0 ? view : null;
        if (!(view instanceof ViewGroup)) return null;

        View best = null;
        long bestArea = 0;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View surface = mostVisibleSurface(group.getChildAt(i));
            long area = visibleArea(surface);
            if (area > bestArea) {
                bestArea = area;
                best = surface;
            }
        }
        return best;
    }

    /** How many pixels of {@code view} are on screen, after clipping by its parents and the screen. */
    private static long visibleArea(View view) {
        if (view == null) return 0;
        Rect visible = new Rect();
        if (!view.getGlobalVisibleRect(visible)) return 0;
        return (long) visible.width() * visible.height();
    }

    private static boolean isOnScreen(View view) {
        return view.isAttachedToWindow() && view.isShown() && visibleArea(view) > 0;
    }

    private static String describe(View view) {
        Rect visible = new Rect();
        view.getGlobalVisibleRect(visible);
        return view.getWidth() + "x" + view.getHeight() + ", visible " + visible.toShortString();
    }

    /**
     * Root views of all the app's windows, the activity first. Read from WindowManagerGlobal; if
     * Android blocks that, only the activity's window is searched.
     */
    private static List<View> rootViews(Activity activity) {
        List<View> roots = new ArrayList<>();
        roots.add(activity.getWindow().getDecorView());
        try {
            Class<?> global = Class.forName("android.view.WindowManagerGlobal");
            Object instance = global.getMethod("getInstance").invoke(null);
            Field views = global.getDeclaredField("mViews");
            views.setAccessible(true);
            for (Object view : (List<?>) views.get(instance)) {
                if (view instanceof View && !roots.contains(view)) roots.add((View) view);
            }
        } catch (Throwable t) {
            DebugLog.log("Label: other windows not readable (" + t + "), searching the activity only");
        }
        return roots;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private void registerCallbacks() {
        if (callbacksRegistered) return;
        Application application = currentApplication();
        if (application == null) return;
        callbacksRegistered = true;
        // The screen with the player is usually already showing when the first ad arrives.
        resumedActivity = new WeakReference<>(findResumedActivity());
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(Activity activity) {
                resumedActivity = new WeakReference<>(activity);
            }

            @Override
            public void onActivityPaused(Activity activity) {
                if (resumedActivity.get() == activity) resumedActivity = new WeakReference<>(null);
                if (label != null && label.getContext() == activity) hideNow();
            }

            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
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
        });
    }

    /** The activity in the foreground, read from ActivityThread; null if Android blocks the lookup. */
    private static Activity findResumedActivity() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null);
            Field activitiesField = activityThreadClass.getDeclaredField("mActivities");
            activitiesField.setAccessible(true);
            Map<?, ?> activities = (Map<?, ?>) activitiesField.get(activityThread);
            if (activities == null) return null;
            for (Object record : activities.values()) {
                Field pausedField = record.getClass().getDeclaredField("paused");
                pausedField.setAccessible(true);
                if (pausedField.getBoolean(record)) continue;
                Field activityField = record.getClass().getDeclaredField("activity");
                activityField.setAccessible(true);
                return (Activity) activityField.get(record);
            }
        } catch (Throwable ignored) {
            // Falls back to the lifecycle callbacks from the next screen change on.
        }
        return null;
    }

    static Application currentApplication() {
        try {
            return (Application) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
