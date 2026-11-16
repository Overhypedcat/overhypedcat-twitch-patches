package app.morphed.extension.twitch.ui;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;

import app.morphed.extension.twitch.App;
import app.morphed.extension.twitch.Included;
import app.morphed.extension.twitch.Messages;
import app.morphed.extension.twitch.musicfilter.SongRemoval;
import app.morphed.extension.twitch.patches.DebugLog;
import app.morphed.extension.twitch.settings.PatchSettings;

/**
 * The patches' parts of the video player's controls, laid over them and shown, moved and faded with them:
 * <ul>
 *     <li>In full screen, a lock button after the top row's left-hand buttons. Locked, the screen stays
 *     horizontal and touches don't reach the player; three quick taps unlock (each tap shows a padlock with the
 *     taps so far).</li>
 *     <li>While song removal is on for the stream, a pill next to the player's own (uptime, viewers) with what
 *     it is doing.</li>
 * </ul>
 * The controls are React Native: found by their test ids. They are taken down each time they hide and built anew
 * each time they show, so they are looked up again on every frame they aren't there.
 */
public final class PlayerOverlay {
    private static final int TAG_KEY = 0x7e000102;
    /** The controls of the player (classic, and the vertical view's). */
    private static final String[] OVERLAYS = {"player-controls-overlay", "skyline-controls-overlay"};
    private static final String TAP_LAYER = "player-controls-tap-layer";
    private static final String BACK = "player-controls-back";
    private static final String SCALE = "player-controls-scale-video";
    private static final String STATS = "viewer-stats";
    /** The player's own pills, each of them also a child of the controls. */
    private static final String STATS_PARTS = "viewer-stats-";
    /** The vertical view's top bar (its controls may have neither a back button nor pills of their own). */
    private static final String VERTICAL_TOP_BAR = "skyline-top-bar";
    /** The controls' parts are their children (classic); looked for a little deeper for other layouts. */
    private static final int PART_DEPTH = 3;
    private static final long HINT_MS = 1200;
    /** The window is looked through for the controls at most this often while they aren't up. */
    private static final long SEARCH_GAP_MS = 60;

    private static int testIdKey;

    private final Activity activity;
    private final Theme theme;
    private final ViewGroup decor;
    private final FrameLayout layer;
    private final LockIcon lockButton;
    private final LinearLayout pill;
    private final NoteIcon pillIcon;
    private final TextView pillText;
    private final View shield;
    private final LinearLayout hint;
    private final LockIcon hintIcon;
    private final View[] hintDots = new View[TripleTap.NEEDED];
    private final TripleTap taps = new TripleTap();
    private final int[] at = new int[2], other = new int[2], layerAt = new int[2];
    private final Runnable fadeHint, followLater;
    private WeakReference<View> controls = new WeakReference<>(null), tapLayer = new WeakReference<>(null);
    private long searchedAt;
    private boolean followQueued;
    private boolean locked;
    private int savedOrientation;
    private SongPillText shownPill;
    private boolean noPillPlaceLogged;
    private boolean failureShown;

    private PlayerOverlay(Activity activity) {
        this.activity = activity;
        theme = new Theme(activity);
        decor = (ViewGroup) activity.getWindow().getDecorView();
        layer = new FrameLayout(activity);
        layer.setClipChildren(false);
        // Not clickable: touches go through it to the player, except on its own button and the lock's shield.

        shield = new View(activity);
        shield.setVisibility(View.GONE);
        shield.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) lockedTap();
            return true; // locked: nothing reaches the player
        });
        layer.addView(shield, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        lockButton = new LockIcon(activity, theme.dpf(24));
        lockButton.setOpen(true);
        lockButton.setClickable(true);
        lockButton.setContentDescription("Lock the screen");
        lockButton.setVisibility(View.INVISIBLE);
        lockButton.setOnClickListener(v -> {
            try {
                lock();
            } catch (Throwable t) {
                failed(t);
            }
        });
        layer.addView(lockButton, new FrameLayout.LayoutParams(theme.dp(40), theme.dp(40)));

        // As the player's own pills: black, mostly opaque, a white glyph and plain white text.
        pill = new LinearLayout(activity);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(theme.dp(5), 0, theme.dp(8), 0);
        pill.setBackground(new Squircle(0xCC000000, theme.dpf(7)));
        pill.setVisibility(View.INVISIBLE);
        pillIcon = new NoteIcon(activity);
        LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(theme.dp(20), theme.dp(20));
        iconSize.rightMargin = theme.dp(3);
        pill.addView(pillIcon, iconSize);
        pillText = new TextView(activity);
        pillText.setTextColor(0xFFFFFFFF);
        pillText.setTextSize(14);
        pillText.setTypeface(TwitchFonts.regular(activity));
        pillText.setIncludeFontPadding(false);
        pillText.setSingleLine(true);
        pillText.setEllipsize(TextUtils.TruncateAt.END);
        pill.addView(pillText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        layer.addView(pill, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, theme.dp(28)));

        hint = new LinearLayout(activity);
        hint.setOrientation(LinearLayout.VERTICAL);
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        hint.setPadding(theme.dp(18), theme.dp(14), theme.dp(18), theme.dp(14));
        hint.setBackground(new Squircle(0xCC000000, theme.dpf(20)));
        hint.setAlpha(0f);
        hint.setVisibility(View.GONE);
        hintIcon = new LockIcon(activity, theme.dpf(36));
        hint.addView(hintIcon, new LinearLayout.LayoutParams(theme.dp(40), theme.dp(40)));
        LinearLayout dots = new LinearLayout(activity);
        dots.setPadding(0, theme.dp(8), 0, 0);
        for (int i = 0; i < hintDots.length; i++) {
            View dot = new View(activity);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(theme.dp(7), theme.dp(7));
            size.leftMargin = size.rightMargin = theme.dp(3);
            dots.addView(dot, size);
            hintDots[i] = dot;
        }
        hint.addView(dots);
        layer.addView(hint, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        fadeHint = () -> hint.animate().alpha(0f).setDuration(250).withEndAction(() -> hint.setVisibility(View.GONE));
        followLater = () -> {
            followQueued = false;
            followSafely();
        };

        decor.addView(layer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        // Every frame: the controls come, fade, move and go, and so do ours.
        ViewTreeObserver.OnPreDrawListener follow = () -> {
            followSafely();
            return true;
        };
        decor.getViewTreeObserver().addOnPreDrawListener(follow);
        SongRemoval.StatusListener songs = s -> App.onMain(layer::invalidate);
        PatchSettings.Listener settings = (key, on) -> App.onMain(layer::invalidate);
        layer.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                SongRemoval.removeListener(songs);
                PatchSettings.removeListener(settings);
                decor.getViewTreeObserver().removeOnPreDrawListener(follow);
            }
        });
        SongRemoval.addListener(songs);
        PatchSettings.addListener(settings);
    }

    /** Called on the activity's layout changes (Screens): once a player's controls are there, ours follow them. */
    public static void scan(Activity activity, View root) {
        if (!Included.songRemoval() && !Included.adBlock() && !Included.qualityUnlock()) return;
        if (testIdKey == 0) {
            testIdKey = activity.getResources().getIdentifier("react_test_id", "id", activity.getPackageName());
            if (testIdKey == 0) testIdKey = -1;
        }
        if (testIdKey < 0) return;
        View decor = activity.getWindow().getDecorView();
        PlayerOverlay overlay = (PlayerOverlay) decor.getTag(TAG_KEY);
        if (overlay == null) {
            // (the layer is added over the app's content once and stays: adding it lays the window out again)
            View[] found = new View[2];
            find(root, null, found);
            if (found[0] == null) return;
            overlay = new PlayerOverlay(activity);
            decor.setTag(TAG_KEY, overlay);
        }
        overlay.followSafely();
    }

    /** The screen went away (left, picture in picture): a lock ends with it. */
    public static void paused(Activity activity) {
        PlayerOverlay overlay = (PlayerOverlay) activity.getWindow().getDecorView().getTag(TAG_KEY);
        if (overlay != null && overlay.locked) overlay.unlock("left the screen", false);
    }

    /**
     * The controls on screen and the tap layer under them, in one walk: found[0] the controls, found[1] the tap
     * layer. Views that don't show are passed over (the player keeps old copies of its layouts around).
     */
    private static void find(View view, View skip, View[] found) {
        if (view == skip || view.getVisibility() != View.VISIBLE || (found[0] != null && found[1] != null)) return;
        Object id = view.getTag(testIdKey);
        if (id instanceof String) {
            if (TAP_LAYER.equals(id)) {
                if (found[1] == null && view.getWidth() > 0) found[1] = view;
                return;
            }
            if (isControls((String) id)) {
                if (found[0] == null && view.getWidth() > 0) found[0] = view;
                return;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) find(group.getChildAt(i), skip, found);
        }
    }

    /** A shown part of the controls by its test id, at most this many levels into them. */
    private static View part(ViewGroup group, String id, int depth) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (!alive(child)) continue;
            if (id.equals(child.getTag(testIdKey))) return child;
            if (depth > 1 && child instanceof ViewGroup) {
                View found = part((ViewGroup) child, id, depth - 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    static boolean isControls(String testId) {
        for (String id : OVERLAYS) if (id.equals(testId)) return true;
        return false;
    }

    /** How much of a view shows: its own alpha and its parents' (the controls fade as a whole). */
    private static float shownAlpha(View view) {
        if (view == null || !view.isAttachedToWindow() || !view.isShown()) return 0f;
        float alpha = view.getAlpha();
        for (ViewParent p = view.getParent(); p instanceof View && alpha > 0f; p = p.getParent()) {
            alpha *= ((View) p).getAlpha();
        }
        return alpha;
    }

    private static boolean alive(View view) {
        return view != null && view.isAttachedToWindow() && view.isShown() && view.getWidth() > 0;
    }

    private boolean pictureInPicture() {
        return Build.VERSION.SDK_INT >= 24 && activity.isInPictureInPictureMode();
    }

    private boolean fullScreen() {
        return activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
                && !pictureInPicture();
    }

    private void followSafely() {
        if (failureShown) return; // it broke and said so: left alone from then on
        try {
            follow();
        } catch (Throwable t) {
            failed(t);
        }
    }

    /** The controls as they are now, looked up again when the ones known are gone. */
    private View currentControls() {
        View known = controls.get();
        if (alive(known)) return known;
        long now = SystemClock.uptimeMillis();
        if (now - searchedAt < SEARCH_GAP_MS) {
            // Too soon to look again: looked for once more after the gap (no frame may come by itself).
            if (!followQueued) {
                followQueued = true;
                layer.postDelayed(followLater, SEARCH_GAP_MS);
            }
            return null;
        }
        searchedAt = now;
        View[] found = new View[2];
        find(decor, layer, found);
        controls = new WeakReference<>(found[0]);
        if (found[1] != null) tapLayer = new WeakReference<>(found[1]);
        return found[0];
    }

    private void follow() {
        View controls = currentControls();
        float alpha = pictureInPicture() ? 0f : shownAlpha(controls);
        boolean shown = alpha > 0.02f && controls instanceof ViewGroup;
        if (locked && !fullScreen()) unlock("left full screen", false);
        else if (locked && tapLayer.get() != null && !alive(tapLayer.get()) && controls == null) {
            unlock("the player closed", false);
        }
        boolean lockShown = false, pillShown = false;
        if (shown && !locked) {
            layer.getLocationOnScreen(layerAt);
            ViewGroup group = (ViewGroup) controls;
            View back = part(group, BACK, PART_DEPTH), scale = part(group, SCALE, PART_DEPTH);
            View stats = part(group, STATS, PART_DEPTH);
            lockShown = fullScreen() && placeLock(back, scale);
            pillShown = Included.songRemoval() && PatchSettings.songRemoval() && placePill(group, stats, back);
        }
        show(lockButton, lockShown, alpha);
        show(pill, pillShown, alpha);
    }

    private static void show(View view, boolean shown, float alpha) {
        if (shown) view.setAlpha(alpha);
        int wanted = shown ? View.VISIBLE : View.INVISIBLE; // (never GONE: that would lay the window out again)
        if (view.getVisibility() != wanted) view.setVisibility(wanted);
    }

    /** The lock button: after the top row's left-hand buttons, as big as they are. */
    private boolean placeLock(View back, View scale) {
        View last = scale != null ? scale : back;
        if (last == null) return false;
        int first = -1;
        if (scale != null && back != null) {
            back.getLocationOnScreen(at);
            first = at[0];
        }
        last.getLocationOnScreen(at);
        place(lockButton, ControlsLayout.lockLeft(first, at[0], last.getWidth()), at[1], last.getWidth(), last.getHeight());
        return true;
    }

    /** The pill: one more of the player's own pills, after them; or on a line of its own above them. */
    private boolean placePill(ViewGroup controls, View stats, View back) {
        SongRemoval.Status status = SongRemoval.status();
        SongPillText words = SongPillText.of(status.step, status.problem);
        if (shownPill == null || !words.text.equals(shownPill.text) || words.tone != shownPill.tone) {
            shownPill = words;
            pillText.setText(words.text);
            pillIcon.setColor(words.tone == SongPillText.Tone.PROBLEM ? 0xFFFF6B6B : 0xFFFFFFFF);
            pillText.setTextColor(words.tone == SongPillText.Tone.PROBLEM ? 0xFFFFB3B3 : 0xFFFFFFFF);
        }
        controls.getLocationOnScreen(at);
        int controlsLeft = at[0], controlsRight = at[0] + controls.getWidth();
        int wanted = wantedWidth();
        if (stats == null) {
            // Controls without the player's pills (the vertical view's may be): under the back button or the
            // top bar, at its left; without those too, there's no telling where it wouldn't cover something.
            View above = back != null ? back : part(controls, VERTICAL_TOP_BAR, PART_DEPTH);
            if (above == null) {
                if (!noPillPlaceLogged) {
                    noPillPlaceLogged = true;
                    DebugLog.log("PROBLEM Player: these controls have no place for the song pill");
                }
                return false;
            }
            above.getLocationOnScreen(at);
            int gap = theme.dp(4), left = Math.max(at[0], controlsLeft) + (above == back ? 0 : theme.dp(12));
            place(pill, left, at[1] + above.getHeight() + gap,
                    Math.min(wanted, Math.max(0, controlsRight - theme.dp(12) - left)), theme.dp(28));
            return true;
        }
        stats.getLocationOnScreen(at);
        int statsLeft = at[0], statsTop = at[1], statsRight = at[0] + stats.getWidth(), statsHeight = stats.getHeight();
        // The space the player leaves between its two pills, and the next control in their row.
        int gap = theme.dp(4), nextLeft = controlsRight;
        View firstPill = null;
        for (int i = 0; i < controls.getChildCount(); i++) {
            View child = controls.getChildAt(i);
            if (child == stats || !alive(child)) continue;
            child.getLocationOnScreen(at);
            Object id = child.getTag(testIdKey);
            if (id instanceof String && ((String) id).startsWith(STATS_PARTS)) {
                if (firstPill == null) {
                    firstPill = child;
                } else {
                    firstPill.getLocationOnScreen(other);
                    int between = at[0] - (other[0] + firstPill.getWidth());
                    if (between > 0 && between < theme.dp(16)) gap = between;
                }
                continue;
            }
            if (ControlsLayout.inRowAfter(at[0], at[1], at[1] + child.getHeight(), statsTop, statsTop + statsHeight,
                    statsRight)) {
                nextLeft = Math.min(nextLeft, at[0]);
            }
        }
        ControlsLayout.Pill spot = ControlsLayout.pill(statsLeft, statsTop, statsRight, statsHeight, gap, nextLeft,
                controlsRight, wanted, shownPill.tone == SongPillText.Tone.PROBLEM);
        if (spot.maxWidth < theme.dp(48) || spot.top < 0 || spot.left < controlsLeft) return false;
        place(pill, spot.left, spot.top, Math.min(wanted, spot.maxWidth), statsHeight);
        return true;
    }

    /** The pill's width with its whole text. */
    private int wantedWidth() {
        float text = pillText.getPaint().measureText(pillText.getText().toString());
        return pill.getPaddingLeft() + pill.getPaddingRight() + theme.dp(23) + (int) Math.ceil(text) + 1;
    }

    private void place(View view, int screenX, int screenY, int width, int height) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
        if (params.width != width || params.height != height) {
            params.width = width;
            params.height = height;
            view.setLayoutParams(params);
        }
        view.setTranslationX(screenX - layerAt[0]);
        view.setTranslationY(screenY - layerAt[1]);
    }

    private void lock() {
        if (locked) return;
        locked = true;
        taps.reset();
        savedOrientation = activity.getRequestedOrientation();
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        // (the player's controls fade by themselves in a moment; nothing brings them back until unlocked)
        shield.setVisibility(View.VISIBLE);
        show(lockButton, false, 0f);
        show(pill, false, 0f);
        hintIcon.setOpen(false);
        showHint(0);
        DebugLog.log("Player: screen locked");
    }

    private void lockedTap() {
        int count = taps.tap(SystemClock.uptimeMillis());
        if (count >= TripleTap.NEEDED) unlock("three taps", true);
        else showHint(count);
    }

    private void unlock(String why, boolean byUser) {
        if (!locked) return;
        locked = false;
        taps.reset();
        shield.setVisibility(View.GONE);
        activity.setRequestedOrientation(savedOrientation);
        layer.removeCallbacks(fadeHint);
        if (byUser) {
            // The padlock opens for a moment.
            hintIcon.setOpen(true);
            showHint(TripleTap.NEEDED);
        } else {
            fadeHint.run();
        }
        DebugLog.log("Player: screen unlocked (" + why + ")");
    }

    /** The padlock in the middle, with the taps so far as filled dots; it fades after a moment. */
    private void showHint(int count) {
        for (int i = 0; i < hintDots.length; i++) {
            hintDots[i].setBackground(new Squircle(i < count ? 0xFFFFFFFF : 0x59FFFFFF, theme.dpf(3.5f)));
        }
        hint.animate().cancel();
        hint.setVisibility(View.VISIBLE);
        hint.setAlpha(1f);
        layer.removeCallbacks(fadeHint);
        layer.postDelayed(fadeHint, HINT_MS);
    }

    private void failed(Throwable t) {
        Log.e(App.TAG, "The player's lock and song pill failed", t);
        try {
            if (locked) unlock("an error", false); // never left locked without a way out
            show(lockButton, false, 0f);
            show(pill, false, 0f);
        } catch (Throwable again) {
            locked = false;
            shield.setVisibility(View.GONE);
        }
        if (failureShown) return;
        failureShown = true;
        DebugLog.log("PROBLEM Player overlay: " + Log.getStackTraceString(t));
        Toast.makeText(activity, "The screen lock and song pill stopped working: " + Messages.of(t),
                Toast.LENGTH_LONG).show();
    }
}
