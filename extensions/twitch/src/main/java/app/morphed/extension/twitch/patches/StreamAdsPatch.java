package app.morphed.extension.twitch.patches;

import android.util.Log;

import app.morphed.extension.twitch.adblock.AccessTokenClient;
import app.morphed.extension.twitch.adblock.AdBlocker;
import app.morphed.extension.twitch.adblock.BackupStreamFinder;
import app.morphed.extension.twitch.adblock.Http;
import app.morphed.extension.twitch.adblock.Logger;
import app.morphed.extension.twitch.adblock.Problem;
import app.morphed.extension.twitch.adblock.UrlConnectionHttp;

/**
 * Entry point used by the patched IVS HTTP client.
 */
@SuppressWarnings("unused")
public final class StreamAdsPatch {
    private static final String TAG = "TwitchMorphed";

    private static final Problem.Reporter PROBLEMS = new ProblemNotifier();
    private static final AdBreakIndicator INDICATOR = new AdBreakIndicator();
    private static final AdBlocker AD_BLOCKER = createAdBlocker();

    private StreamAdsPatch() {
    }

    public static boolean shouldInspect(String url) {
        try {
            if (AD_BLOCKER.shouldInspect(url)) return true;
            AD_BLOCKER.checkRequest(url);
            return false;
        } catch (Throwable t) {
            PROBLEMS.report(Problem.PROCESSING_FAILED, t.toString());
            return false;
        }
    }

    /** The playlist to give the player, or the original body if anything goes wrong. */
    public static String process(String url, String body) {
        try {
            return AD_BLOCKER.process(url, body);
        } catch (Throwable t) {
            Log.e(TAG, "Playlist processing failed for " + url, t);
            PROBLEMS.report(Problem.PROCESSING_FAILED, t.toString());
            return body;
        }
    }

    private static AdBlocker createAdBlocker() {
        Logger logger = message -> Log.d(TAG, message);
        Http http = new UrlConnectionHttp();
        BackupStreamFinder backups = new BackupStreamFinder(
                http, new AccessTokenClient(http), logger, System::currentTimeMillis, PROBLEMS);
        AdBlocker.BreakListener breaks = new AdBlocker.BreakListener() {
            @Override
            public void onBlocking(String quality) {
                INDICATOR.show(quality);
            }

            @Override
            public void onBreakOver(boolean swapped) {
                INDICATOR.hide();
                if (swapped) PlayerRefresher.refreshPlayingPlayers();
            }
        };
        return new AdBlocker(backups, logger, System::currentTimeMillis, breaks, PROBLEMS);
    }
}
