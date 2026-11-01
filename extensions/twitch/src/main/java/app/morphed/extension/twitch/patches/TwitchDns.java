package app.morphed.extension.twitch.patches;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphed.extension.twitch.dns.HostBlocklist;

/**
 * Replaces the {@code InetAddress.getAllByName} call in Twitch's OkHttp connection code. Tracking
 * servers are always refused; ad servers only while a stream is being watched (the main page is left
 * alone); everything else is looked up by the phone as usual.
 */
@SuppressWarnings("unused")
public final class TwitchDns {
    private static final long REPORT_EVERY_MS = 60_000;

    /** Per blocked host: attempts since the last log line. Shows whether Twitch keeps retrying. */
    private static final Map<String, AtomicInteger> ATTEMPTS = new ConcurrentHashMap<>();
    private static final Map<String, Long> REPORTED_AT = new ConcurrentHashMap<>();

    private TwitchDns() {
    }

    public static InetAddress[] getAllByName(String host) throws UnknownHostException {
        boolean adServer = HostBlocklist.isAdServer(host);
        if (shouldBlock(host, adServer && StreamAdsPatch.isWatchingStream())) {
            report(host);
            if (adServer) StreamAdsPatch.adBlocked();
            throw new UnknownHostException("Blocked by Twitch Morphed: " + host);
        }
        return InetAddress.getAllByName(host);
    }

    /** Ad servers only while watching a stream; tracking servers always. */
    public static boolean shouldBlock(String host, boolean watchingStream) {
        if (!HostBlocklist.isBlocked(host)) return false;
        return !HostBlocklist.isAdServer(host) || watchingStream;
    }

    private static void report(String host) {
        int attempts = ATTEMPTS.computeIfAbsent(host, h -> new AtomicInteger()).incrementAndGet();
        long now = System.currentTimeMillis();
        Long last = REPORTED_AT.get(host);
        if (last != null && now - last < REPORT_EVERY_MS) return;
        REPORTED_AT.put(host, now);
        ATTEMPTS.get(host).addAndGet(-attempts);
        StreamAdsPatch.LOGGER.log("Blocked " + host + " (" + attempts + (last == null ? " attempt)" : " attempts in the last minute)"));
    }
}
