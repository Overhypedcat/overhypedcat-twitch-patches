package app.morphed.extension.twitch.dns;

import java.net.InetAddress;
import java.net.UnknownHostException;

import app.morphed.extension.twitch.adblock.Logger;

/**
 * The Twitch app's own DNS: ad and tracking servers are refused, everything else is looked up over
 * encrypted DNS, and only if that fails the phone's DNS answers.
 */
public final class AppDns {
    public interface SystemLookup {
        InetAddress[] lookup(String host) throws UnknownHostException;
    }

    private final SystemLookup system;
    private final DohResolver doh;
    private final Logger logger;

    public AppDns(SystemLookup system, DohResolver doh, Logger logger) {
        this.system = system;
        this.doh = doh;
        this.logger = logger;
    }

    public InetAddress[] lookup(String host) throws UnknownHostException {
        if (HostBlocklist.isBlocked(host)) {
            logger.log("Blocked " + host);
            throw new UnknownHostException("Blocked by Twitch Morphed: " + host);
        }
        if (DohResolver.canResolve(host)) {
            InetAddress[] addresses = doh.resolve(host);
            if (addresses != null) return addresses;
            logger.log("Encrypted DNS had no answer for " + host + ", asking the phone's DNS");
        }
        return system.lookup(host);
    }
}
