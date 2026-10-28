package app.morphed.extension.twitch.patches;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.amazonaws.ivs.player.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Keeps track of the IVS players and nudges the playing ones back to the live edge after an
 * ad break, so audio and video line up again after switching playlists.
 */
@SuppressWarnings("unused")
public final class PlayerRefresher {
    private static final String TAG = "TwitchMorphed";
    private static final long PLAY_DELAY_MS = 150;

    private static final Map<Player, Boolean> PLAYERS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private PlayerRefresher() {
    }

    /** Injected at the end of the CorePlayerImpl constructor. */
    public static void register(Player player) {
        if (player != null) PLAYERS.put(player, Boolean.TRUE);
    }

    public static void refreshPlayingPlayers() {
        MAIN.post(() -> {
            List<Player> players;
            synchronized (PLAYERS) {
                players = new ArrayList<>(PLAYERS.keySet());
            }
            for (Player player : players) {
                try {
                    if (player.getState() != Player.State.PLAYING) continue;
                    player.pause();
                    MAIN.postDelayed(player::play, PLAY_DELAY_MS);
                    Log.d(TAG, "Refreshed player after ad break");
                } catch (Throwable t) {
                    Log.e(TAG, "Player refresh failed", t);
                }
            }
        });
    }
}
