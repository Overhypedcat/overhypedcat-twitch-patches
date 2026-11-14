package app.morphed.extension.twitch.settings;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.List;

import app.morphed.extension.twitch.patches.DebugLog;
import app.morphed.extension.twitch.ui.Squircle;
import app.morphed.extension.twitch.ui.Theme;
import app.morphed.extension.twitch.unlock.QualityUnlock;
import app.morphed.extension.twitch.unlock.VpnServers;

/** The VPN servers (WireGuard configs) the quality unlock uses: added from a file or the clipboard, removed. */
public final class VpnServersPage {
    private static WeakReference<VpnServersPage> open = new WeakReference<>(null);

    private final Activity activity;
    private final Theme theme;
    private final Dialog dialog;
    private final LinearLayout list;
    private final TextView message;
    private boolean changed; // servers added or removed: the Settings row's count is rebuilt on leaving

    private VpnServersPage(Activity activity) {
        this.activity = activity;
        theme = new Theme(activity);
        dialog = new Dialog(activity, theme.dark ? android.R.style.Theme_DeviceDefault_NoActionBar
                : android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout page = new LinearLayout(activity);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(theme.background);
        page.setFitsSystemWindows(true);

        LinearLayout bar = new LinearLayout(activity);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(theme.dp(4), theme.dp(8), theme.dp(16), theme.dp(8));
        View back = new Chevron(activity, theme);
        back.setContentDescription("Back");
        back.setOnClickListener(v -> dialog.dismiss());
        bar.addView(back);
        TextView title = new TextView(activity);
        title.setText("VPN servers");
        title.setTextColor(theme.text);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(theme.dp(8), 0, 0, 0);
        bar.addView(title);
        page.addView(bar);

        ScrollView scroll = new ScrollView(activity);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(theme.dp(16), theme.dp(8), theme.dp(16), theme.dp(24));
        list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        body.addView(list);

        message = new TextView(activity);
        message.setTextSize(14);
        message.setPadding(theme.dp(4), theme.dp(12), theme.dp(4), 0);
        message.setVisibility(View.GONE);
        body.addView(message);

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, theme.dp(16), 0, 0);
        TextView fromFile = button("Add from file", true);
        fromFile.setOnClickListener(v -> {
            try {
                activity.startActivity(new Intent(activity, VpnImportActivity.class));
            } catch (Throwable t) {
                say("The file picker couldn't be opened: " + t, true);
            }
        });
        TextView paste = button("Paste config", false);
        paste.setOnClickListener(v -> paste());
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, theme.dp(48), 1);
        buttons.addView(fromFile, half);
        View gap = new View(activity);
        buttons.addView(gap, new LinearLayout.LayoutParams(theme.dp(12), 1));
        buttons.addView(paste, new LinearLayout.LayoutParams(0, theme.dp(48), 1));
        body.addView(buttons);

        scroll.addView(body);
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        dialog.setContentView(page);
        dialog.setOnDismissListener(d -> {
            open = new WeakReference<>(null);
            if (changed) app.morphed.extension.twitch.ui.Screens.refreshSettingsRows();
        });
        refresh();
    }

    public static void show(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        VpnServersPage page = new VpnServersPage(activity);
        open = new WeakReference<>(page);
        page.dialog.show();
    }

    /** A server was added from a file: shown at once if the page is open. */
    static void added(String name, String problem) {
        VpnServersPage page = open.get();
        if (page == null) return;
        if (problem == null) page.changed = true;
        page.refresh();
        if (problem != null) page.say(problem, true);
        else page.say("Added " + name, false);
    }

    private TextView button(String text, boolean filled) {
        TextView button = new TextView(activity);
        button.setText(text);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(filled ? 0xFFFFFFFF : theme.text);
        Squircle shape = new Squircle(filled ? Theme.PURPLE : theme.surface, theme.dpf(12));
        if (!filled) shape.border(theme.hairline, theme.dpf(1));
        button.setBackground(shape);
        button.setClickable(true);
        return button;
    }

    private void say(String text, boolean problem) {
        message.setText(text);
        message.setTextColor(problem ? theme.danger : theme.secondary);
        message.setVisibility(View.VISIBLE);
    }

    private void paste() {
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        CharSequence text = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).coerceToText(activity) : null;
        if (text == null || text.length() == 0) {
            say("The clipboard is empty: copy a WireGuard config first", true);
            return;
        }
        VpnServers servers = QualityUnlock.servers();
        String name = "Server " + (servers.names().size() + 1);
        try {
            String kept = servers.add(name, text.toString());
            QualityUnlock.serverAdded();
            changed = true;
            DebugLog.log("VPN servers: added " + kept + " from the clipboard");
            refresh();
            say("Added " + kept, false);
        } catch (Exception e) {
            say("Not added: " + e.getMessage(), true);
        }
    }

    private void refresh() {
        list.removeAllViews();
        List<String> names = QualityUnlock.servers().names();
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(new Squircle(theme.surface, theme.dpf(14)).border(theme.hairline, theme.dpf(1)));
        if (names.isEmpty()) {
            TextView none = new TextView(activity);
            none.setText("No servers yet");
            none.setTextColor(theme.secondary);
            none.setTextSize(15);
            none.setPadding(theme.dp(16), theme.dp(18), theme.dp(16), theme.dp(18));
            card.addView(none);
        }
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(theme.dp(16), theme.dp(6), theme.dp(4), theme.dp(6));
            row.setMinimumHeight(theme.dp(56));
            TextView label = new TextView(activity);
            label.setText(name);
            label.setTextColor(theme.text);
            label.setTextSize(16);
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            View remove = new Cross(activity, theme);
            remove.setContentDescription("Remove " + name);
            remove.setOnClickListener(v -> {
                if (QualityUnlock.servers().remove(name)) {
                    QualityUnlock.serverRemoved(name);
                    changed = true;
                    DebugLog.log("VPN servers: removed " + name);
                    refresh();
                    say("Removed " + name, false);
                } else {
                    say(name + " couldn't be removed", true);
                }
            });
            row.addView(remove);
            card.addView(row);
            if (i < names.size() - 1) {
                View line = new View(activity);
                line.setBackgroundColor(theme.hairline);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, theme.dp(1) / 2));
                lp.leftMargin = theme.dp(16);
                card.addView(line, lp);
            }
        }
        list.addView(card);
    }

    /** A back chevron, drawn. */
    private static final class Chevron extends View {
        private final Theme theme;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Chevron(Context context, Theme theme) {
            super(context);
            this.theme = theme;
            paint.setColor(theme.text);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(theme.dpf(2));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            setClickable(true);
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(theme.dp(48), theme.dp(48));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = theme.dpf(7);
            canvas.drawLine(cx + r / 2, cy - r, cx - r / 2, cy, paint);
            canvas.drawLine(cx - r / 2, cy, cx + r / 2, cy + r, paint);
        }
    }

    /** A remove cross, drawn. */
    private static final class Cross extends View {
        private final Theme theme;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Cross(Context context, Theme theme) {
            super(context);
            this.theme = theme;
            paint.setColor(theme.secondary);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(theme.dpf(1.8f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            setClickable(true);
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(theme.dp(48), theme.dp(48));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = theme.dpf(5.5f);
            canvas.drawLine(cx - r, cy - r, cx + r, cy + r, paint);
            canvas.drawLine(cx - r, cy + r, cx + r, cy - r, paint);
        }
    }
}
