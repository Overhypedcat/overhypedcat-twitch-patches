package app.morphed.extension.twitch.unlock;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

import app.morphed.extension.twitch.App;

/**
 * The viewer's Twitch login, read where the app keeps it (its own preferences, "authToken_v2"), at the moment
 * it's needed and never kept.
 */
final class TwitchLogin {
    private static final String KEY = "authToken_v2";
    private static final String USUAL_FILE = "twitch_user_prefs";

    private TwitchLogin() {
    }

    private static volatile String file = USUAL_FILE; // where it was found last

    static String token() {
        Context context = App.application();
        if (context == null) return null;
        String token = read(context, file);
        if (token != null) return token;
        // Another app version may keep it in another file: look through the app's preference files.
        File folder = new File(context.getApplicationInfo().dataDir, "shared_prefs");
        String[] files = folder.list((dir, name) -> name.endsWith(".xml"));
        if (files == null) return null;
        for (String file : files) {
            String name = file.substring(0, file.length() - 4);
            token = read(context, name);
            if (token != null) {
                TwitchLogin.file = name;
                return token;
            }
        }
        return null;
    }

    private static String read(Context context, String name) {
        try {
            SharedPreferences preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE);
            Object value = preferences.getAll().get(KEY);
            return value instanceof String && !((String) value).isEmpty() ? (String) value : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
