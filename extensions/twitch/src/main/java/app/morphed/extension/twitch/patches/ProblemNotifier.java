package app.morphed.extension.twitch.patches;

import android.util.Log;

import app.morphed.extension.twitch.adblock.AdBlockStatus;
import app.morphed.extension.twitch.adblock.Problem;

/**
 * Tells the user what went wrong with the ad blocker (and whether it needs an update), each time it happens: on the
 * line under the Ad blocker switch in the player's Options, as song removal's problems are (no toast over the
 * stream), and in the log.
 */
final class ProblemNotifier implements Problem.Reporter {
    private static final String TAG = "TwitchMorphed";

    @Override
    public void report(Problem problem, String detail) {
        Log.w(TAG, problem.code + " " + problem.description + ": " + detail);
        DebugLog.log("PROBLEM " + problem.code + " " + problem.description + ": " + detail);
        AdBlockStatus.problem(problem, detail);
    }
}
