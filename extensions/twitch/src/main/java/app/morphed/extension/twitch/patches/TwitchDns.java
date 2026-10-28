package app.morphed.extension.twitch.patches;

import java.net.InetAddress;
import java.net.UnknownHostException;

import app.morphed.extension.twitch.dns.HostBlocklist;

/**
 * Replaces the {@code InetAddress.getAllByName} call in Twitch's OkHttp connection code: ad and
 * tracking servers are refused, everything else is looked up by the phone as usual.
 */
@SuppressWarnings("unused")
public final class TwitchDns {
    private TwitchDns() {
    }

    public static InetAddress[] getAllByName(String host) throws UnknownHostException {
        if (HostBlocklist.isBlocked(host)) {
            StreamAdsPatch.LOGGER.log("Blocked " + host);
            throw new UnknownHostException("Blocked by Twitch Morphed: " + host);
        }
        return InetAddress.getAllByName(host);
    }
}
