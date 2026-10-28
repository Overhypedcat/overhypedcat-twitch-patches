package app.morphed.extension.twitch.patches;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.util.EnumSet;
import java.util.Set;

import app.morphed.extension.twitch.adblock.Problem;

/**
 * Tells the user once per app launch and problem that the ad blocker needs an update.
 */
final class ProblemNotifier implements Problem.Reporter {
    private static final String TAG = "TwitchMorphed";

    private final Set<Problem> shown = EnumSet.noneOf(Problem.class);
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public void report(Problem problem, String detail) {
        Log.w(TAG, problem.code + " " + problem.description + ": " + detail);
        synchronized (shown) {
            if (!shown.add(problem)) return;
        }
        main.post(() -> {
            Context context = applicationContext();
            if (context == null) return;
            Toast.makeText(context, "Ad blocker needs an update (" + problem.code + ")\n" + problem.description,
                    Toast.LENGTH_LONG).show();
        });
    }

    private static Context applicationContext() {
        try {
            return (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
