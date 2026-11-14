package app.morphed.extension.twitch.unlock;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphed.extension.twitch.App;
import app.morphed.extension.twitch.Included;
import app.morphed.extension.twitch.net.Web;
import app.morphed.extension.twitch.patches.DebugLog;
import app.morphed.extension.twitch.settings.PatchSettings;
import app.morphed.extension.twitch.vpn.VpnTunnel;
import app.morphed.extension.twitch.vpn.WgConfig;

/**
 * The quality unlock, the way Mor does it: Twitch offers Enhanced Broadcasting's higher qualities (1440p) only to
 * logged-in viewers in some countries who can play HEVC. When a stream starts, its playlist is also asked for
 * through the user's WireGuard VPN; when that one offers more, the player gets it, and that stream's video then
 * comes through the same tunnel. Nothing else in the app goes through it.
 * <p>
 * Each stream is decided once, on its first playlist request: later requests for it (the ad blocker's reloads,
 * the mini player, picture in picture) follow that decision without asking again. A server that didn't answer is
 * left alone for a while, so streams don't each wait for it. An idle tunnel is closed.
 */
public final class QualityUnlock {
    static final int TUNNEL_TIMEOUT_MS = 5_000;
    static final int WAIT_MS = 8_000; // the longest a stream's start waits for the unlock, everything included
    static final long FAILED_SERVERS_REST_MS = 3 * 60_000; // no server answered: streams start direct this long
    static final long IDLE_CLOSE_MS = 5 * 60_000; // a tunnel nothing went through this long is closed
    private static final Pattern CHANNEL = Pattern.compile("/api/v2/channel/hls/([^/.?]+)\\.m3u8|/api/channel/hls/([^/.?]+)\\.m3u8");

    /** What the unlock did for the stream being watched, for the player's Options. */
    public static final class Status {
        public final String text;
        public final boolean problem;

        Status(String text, boolean problem) {
            this.text = text;
            this.problem = problem;
        }
    }

    public interface StatusListener {
        void changed(Status status);
    }

    /** A stream's decision. */
    static final class Decision {
        final boolean viaVpn;

        Decision(boolean viaVpn) {
            this.viaVpn = viaVpn;
        }
    }

    private static final Map<String, Decision> DECIDED = new ConcurrentHashMap<>();
    private static final Set<String> TUNNEL_HOSTS = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final CopyOnWriteArrayList<StatusListener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final UnlockFetcher FETCHER = new UnlockFetcher();
    private static final Object TUNNEL_LOCK = new Object(); // only swaps the reference: no I/O under it
    private static final Object MASTER_LOCK = new Object(); // one stream decided at a time
    private static final ScheduledExecutorService BACKGROUND = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "TwitchMorphed-unlock");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile VpnTunnel tunnel;
    private static volatile String lastChannel;
    private static volatile long lastUsedAt;
    private static volatile long serversFailedAt;
    private static volatile String serversFailure;
    private static volatile Status status = new Status("Off", false);
    private static volatile VpnServers servers;

    static {
        PatchSettings.addListener((key, on) -> {
            if (!PatchSettings.QUALITY_UNLOCK.equals(key)) return;
            if (!on) {
                DECIDED.clear();
                TUNNEL_HOSTS.clear();
                BACKGROUND.execute(QualityUnlock::closeTunnel); // (closing sends packets: never on the main thread)
                publish(new Status("Off", false));
            } else {
                serversFailedAt = 0;
                publish(new Status(servers().names().isEmpty() ? "Add a VPN server in Settings, Patch settings" : "On", false));
            }
        });
        BACKGROUND.scheduleWithFixedDelay(QualityUnlock::tidy, 1, 1, TimeUnit.MINUTES);
    }

    private QualityUnlock() {
    }

    public static VpnServers servers() {
        VpnServers kept = servers;
        if (kept != null) return kept;
        Context context = App.application();
        File folder = context == null ? new File("vpn") : new File(context.getFilesDir(), "vpn");
        VpnServers made = new VpnServers(folder);
        if (context != null) servers = made; // one instance: its locks keep adds and reads apart
        return made;
    }

    public static Status status() {
        return status;
    }

    public static void addListener(StatusListener listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(StatusListener listener) {
        LISTENERS.remove(listener);
    }

    private static void publish(Status next) {
        status = next;
        DebugLog.log("Quality unlock: " + next.text);
        App.onMain(() -> {
            for (StatusListener listener : LISTENERS) listener.changed(next);
        });
    }

    /** The channel of a live usher playlist request, or null. */
    public static String channelOf(String url) {
        if (url == null || !url.contains("usher.ttvnw.net")) return null;
        Matcher matcher = CHANNEL.matcher(url);
        if (!matcher.find()) return null;
        String channel = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        return channel.toLowerCase(java.util.Locale.ROOT);
    }

    /** Whether a request should go through the tunnel: the video of a stream that was unlocked. */
    public static boolean routes(String url) {
        if (TUNNEL_HOSTS.isEmpty() || !Included.qualityUnlock() || !PatchSettings.qualityUnlock()
                || url.contains("usher.ttvnw.net")) return false;
        String host = host(url);
        return host != null && TUNNEL_HOSTS.contains(host);
    }

    static String host(String url) {
        try {
            return new URL(url).getHost();
        } catch (java.net.MalformedURLException e) {
            return null;
        }
    }

    /** The tunnel's HTTP client for routed requests, or null when the tunnel is down. */
    public static Web tunnelWeb() {
        VpnTunnel current = tunnel;
        // Open is enough: keys that expired while the stream was paused are renewed by the next send.
        if (current == null || !current.isOpen()) return null;
        lastUsedAt = System.currentTimeMillis();
        return current.web();
    }

    /** The tunnel failed a routed request: the stream goes on over the normal connection, and that is told. */
    public static void tunnelFailed(String why) {
        if (!status.problem) publish(new Status("The VPN stopped answering (" + why + "), the stream plays without the unlock", true));
    }

    /** The hosts a playlist from the tunnel points at: their requests go through the tunnel too. */
    public static void learnHosts(String playlist) {
        for (String line : playlist.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("http")) {
                String host = host(trimmed);
                if (host != null && !host.contains("usher.")) TUNNEL_HOSTS.add(host);
            }
        }
    }

    /**
     * A stream's master playlist came in over the normal connection: the playlist to give the player (the one
     * through the VPN when it offers more), or the same one. Never throws: the stream must play either way.
     */
    public static String master(String usherUrl, String direct, boolean preview) {
        String channel = channelOf(usherUrl);
        if (channel == null || preview || !Included.qualityUnlock() || !PatchSettings.qualityUnlock()) return direct;
        synchronized (MASTER_LOCK) {
            try {
                return decide(channel, direct);
            } catch (Throwable t) {
                publish(new Status("The quality unlock ran into an error: " + t, true));
                return direct;
            }
        }
    }

    private static String decide(String channel, String direct) {
        if (!channel.equals(lastChannel)) { // a new stream: the previous one's routing is done with
            lastChannel = channel;
            TUNNEL_HOSTS.clear();
            for (String decided : new ArrayList<>(DECIDED.keySet())) if (!decided.equals(channel)) DECIDED.remove(decided);
        }
        Decision decision = DECIDED.get(channel);
        if (decision != null && !decision.viaVpn) return direct;
        long now = System.currentTimeMillis();
        if (decision == null && now - serversFailedAt < FAILED_SERVERS_REST_MS) {
            long wait = (FAILED_SERVERS_REST_MS - (now - serversFailedAt) + 999) / 1000;
            DECIDED.put(channel, new Decision(false));
            publish(new Status(serversFailure + ", trying again in " + wait + " s", true));
            return direct;
        }
        String token = TwitchLogin.token();
        if (token == null) {
            publish(new Status("Log in to Twitch to unlock higher qualities", true));
            return direct;
        }
        List<String> problems = new ArrayList<>();
        List<WgConfig> configs = servers().configs(problems);
        if (configs.isEmpty()) {
            publish(new Status(problems.isEmpty() ? "Add a VPN server in Settings, Patch settings"
                    : "No usable VPN server: " + problems.get(0), true));
            return direct;
        }
        long deadline = now + WAIT_MS;
        UnlockFetcher.Result result = null;
        String failure = null;
        boolean anyAnswered = false;
        for (WgConfig config : ordered(configs)) {
            int left = (int) (deadline - System.currentTimeMillis());
            if (left < 1500) break;
            VpnTunnel up = ensureTunnel(config, Math.min(TUNNEL_TIMEOUT_MS, left));
            if (up == null) {
                if (failure == null) failure = "Couldn't connect to " + config.name;
                continue;
            }
            anyAnswered = true;
            try {
                result = FETCHER.fetch(up.web(), channel, token, (int) Math.max(500, deadline - System.currentTimeMillis()));
                break;
            } catch (UnlockFetcher.Failed failed) {
                failure = failed.getMessage();
                if (failed.login) break; // the login, not the server: no point trying the others
            }
        }
        if (result == null) {
            DECIDED.put(channel, new Decision(false));
            if (!anyAnswered) { // no server came up: the next streams start at once instead of waiting again
                serversFailedAt = System.currentTimeMillis();
                serversFailure = failure != null ? failure : "No VPN server answered";
            }
            publish(new Status(failure != null ? failure : "No VPN server answered in time", true));
            return direct;
        }
        lastUsedAt = System.currentTimeMillis();
        List<String> extra = UnlockFetcher.unlockedOnly(UnlockFetcher.qualities(direct), result.qualities);
        if (extra.isEmpty()) {
            DECIDED.put(channel, new Decision(false));
            publish(new Status("Nothing locked on " + channel + "'s stream", false));
            return direct;
        }
        VpnTunnel current = tunnel;
        String server = current != null ? current.config.name : "";
        DECIDED.put(channel, new Decision(true));
        learnHosts(result.master);
        publish(new Status("Unlocked " + String.join(", ", extra) + " through " + server, false));
        return result.master;
    }

    /** The server that's up first (no reconnect), then the rest in name order. */
    private static List<WgConfig> ordered(List<WgConfig> configs) {
        VpnTunnel current = tunnel;
        List<WgConfig> out = new ArrayList<>();
        if (current != null && current.isUp()) {
            for (WgConfig config : configs) if (config.name.equals(current.config.name)) out.add(config);
        }
        for (WgConfig config : configs) if (!out.contains(config)) out.add(config);
        return out;
    }

    /** The tunnel to this server, connected when needed (outside any lock: it takes seconds). */
    private static VpnTunnel ensureTunnel(WgConfig config, int timeoutMs) {
        VpnTunnel current = tunnel;
        if (current != null && current.isUp() && current.config.name.equals(config.name)) return current;
        closeTunnel();
        try {
            VpnTunnel opened = VpnTunnel.open(config, timeoutMs);
            synchronized (TUNNEL_LOCK) {
                tunnel = opened;
            }
            DebugLog.log("Quality unlock: tunnel up through " + config.name);
            return opened;
        } catch (Exception e) {
            DebugLog.log("Quality unlock: " + config.name + " failed: " + e.getMessage());
            return null;
        }
    }

    private static void closeTunnel() {
        VpnTunnel closing;
        synchronized (TUNNEL_LOCK) {
            closing = tunnel;
            tunnel = null;
        }
        if (closing != null) closing.close();
    }

    /** Every minute: kept connections idle too long are dropped, and a tunnel nothing used for a while closed. */
    private static void tidy() {
        try {
            VpnTunnel current = tunnel;
            if (current == null) return;
            if (System.currentTimeMillis() - lastUsedAt >= IDLE_CLOSE_MS) {
                DebugLog.log("Quality unlock: tunnel idle, closed");
                closeTunnel();
                TUNNEL_HOSTS.clear();
                // Only the streams that went through it are decided again; "nothing locked" stays decided.
                DECIDED.values().removeIf(decision -> decision.viaVpn);
            } else {
                current.tidy();
            }
        } catch (Throwable t) {
            DebugLog.log("Quality unlock: tidying failed: " + t);
        }
    }

    /** A server was added: the unlock is switched on the first time there is one; a rest after failures ends. */
    public static void serverAdded() {
        serversFailedAt = 0;
        Context context = App.application();
        if (context == null) return;
        SharedPreferences preferences = PatchSettings.preferences(context);
        if (!preferences.contains(PatchSettings.QUALITY_UNLOCK)) PatchSettings.set(PatchSettings.QUALITY_UNLOCK, true);
    }

    /** A server was removed: its tunnel, if it's the one up, is closed. */
    public static void serverRemoved(String name) {
        VpnTunnel current = tunnel;
        if (current != null && current.config.name.equals(name)) {
            TUNNEL_HOSTS.clear();
            DECIDED.clear();
            BACKGROUND.execute(QualityUnlock::closeTunnel);
        }
    }
}
