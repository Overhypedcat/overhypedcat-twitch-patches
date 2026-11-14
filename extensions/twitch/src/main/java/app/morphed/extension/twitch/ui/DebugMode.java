package app.morphed.extension.twitch.ui;

import android.app.Activity;
import android.content.Context;
import android.widget.Toast;

import app.morphed.extension.twitch.App;
import app.morphed.extension.twitch.patches.DebugLog;

/** Debug mode: the log file in Downloads and the live log window, switched together. */
public final class DebugMode {
    private DebugMode() {
    }

    public static boolean isOn(Context context) {
        return DebugLog.isEnabled(context);
    }

    public static void set(Activity activity, boolean on) {
        Context app = activity.getApplicationContext();
        try {
            String file = DebugLog.setEnabled(app, on);
            if (on && DebugLog.isOn()) {
                LogWindow.show(activity);
                DebugLog.log("Debug mode on, logging to Downloads/" + file);
            } else {
                LogWindow.hide();
            }
            // When it couldn't start, DebugLog has already said why.
            if (file != null) Toast.makeText(app, (on ? "Logging to Downloads/" : "Saved Downloads/") + file, Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            android.util.Log.e(App.TAG, "Switching Debug mode failed", t);
            Toast.makeText(app, "Debug mode could not be switched " + (on ? "on" : "off") + ": " + t, Toast.LENGTH_LONG).show();
        }
        Screens.refreshSettingsRows();
    }
}
