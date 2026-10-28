package app.morphed.extension.twitch.patches;

import android.app.Activity;
import android.app.Application;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Map;

/**
 * A small label in the top left corner while an ad break is being blocked.
 */
final class AdBreakIndicator {
    /** Hide the label if no playlist update arrives for this long, for example after leaving the stream. */
    private static final long STALE_MS = 15_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable hideRunnable = this::hideNow;
    private WeakReference<Activity> resumedActivity = new WeakReference<>(null);
    private boolean callbacksRegistered;
    private TextView label;

    void show(String quality) {
        String text = quality == null ? "Blocking ads" : "Blocking ads (" + quality + ")";
        main.post(() -> {
            registerCallbacks();
            Activity activity = resumedActivity.get();
            if (activity == null) return;

            if (label == null || label.getContext() != activity) {
                hideNow();
                label = createLabel(activity);
                ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
                decor.addView(label, layoutParams(activity, decor));
            }
            label.setText(text);
            label.bringToFront();
            main.removeCallbacks(hideRunnable);
            main.postDelayed(hideRunnable, STALE_MS);
        });
    }

    void hide() {
        main.post(this::hideNow);
    }

    private void hideNow() {
        main.removeCallbacks(hideRunnable);
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

    private static FrameLayout.LayoutParams layoutParams(Activity activity, ViewGroup decor) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        int margin = dp(activity, 8);
        int top = 0;
        WindowInsets insets = decor.getRootWindowInsets();
        if (insets != null) top = insets.getSystemWindowInsetTop();
        params.setMargins(margin, top + margin, margin, margin);
        return params;
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
