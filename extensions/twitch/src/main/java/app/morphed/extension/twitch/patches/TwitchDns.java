package app.morphed.extension.twitch.patches;

import android.util.Log;

import java.net.InetAddress;
import java.net.UnknownHostException;

import app.morphed.extension.twitch.adblock.UrlConnectionHttp;
import app.morphed.extension.twitch.dns.AppDns;
import app.morphed.extension.twitch.dns.DohResolver;

/**
 * Replaces the {@code InetAddress.getAllByName} call in Twitch's OkHttp connection code.
 */
@SuppressWarnings("unused")
public final class TwitchDns {
    private static final String TAG = "TwitchMorphed";

    private static final AppDns DNS = new AppDns(
            InetAddress::getAllByName,
            new DohResolver(new UrlConnectionHttp(2_000), StreamAdsPatch.LOGGER, System::currentTimeMillis),
            StreamAdsPatch.LOGGER);

    private TwitchDns() {
    }

    public static InetAddress[] getAllByName(String host) throws UnknownHostException {
        try {
            return DNS.lookup(host);
        } catch (UnknownHostException e) {
            throw e;
        } catch (Throwable t) {
            // Never worse than without the patch: on any problem of our own, ask the phone's DNS directly.
            Log.e(TAG, "DNS lookup failed for " + host, t);
            return InetAddress.getAllByName(host);
        }
    }
}
