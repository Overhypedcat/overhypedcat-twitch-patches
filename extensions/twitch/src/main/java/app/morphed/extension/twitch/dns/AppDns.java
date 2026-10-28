package app.morphed.extension.twitch.dns;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import app.morphed.extension.twitch.adblock.Logger;

/**
 * The Twitch app's own DNS: ad and tracking servers are refused, and every other name is asked of
 * encrypted DNS and the phone's DNS at the same time. The first real answer wins, so neither a slow
 * encrypted DNS nor a slow phone DNS holds the app up. A "blocked" answer from the phone's DNS (an ad
 * blocking DNS answering 0.0.0.0 or ::) does not count while the encrypted answer may still come.
 */
public final class AppDns {
    public interface SystemLookup {
        InetAddress[] lookup(String host) throws UnknownHostException;
    }

    private final SystemLookup system;
    private final DohResolver doh;
    private final ExecutorService executor;
    private final Logger logger;

    public AppDns(SystemLookup system, DohResolver doh, ExecutorService executor, Logger logger) {
        this.system = system;
        this.doh = doh;
        this.executor = executor;
        this.logger = logger;
    }

    public InetAddress[] lookup(String host) throws UnknownHostException {
        if (HostBlocklist.isBlocked(host)) {
            logger.log("Blocked " + host);
            throw new UnknownHostException("Blocked by Twitch Morphed: " + host);
        }
        if (!DohResolver.canResolve(host)) return system.lookup(host);

        CompletionService<InetAddress[]> race = new ExecutorCompletionService<>(executor);
        Future<InetAddress[]> encrypted = race.submit(() -> doh.resolve(host));
        Future<InetAddress[]> phone = race.submit(() -> system.lookup(host));

        InetAddress[] sinkholed = null;
        UnknownHostException phoneFailure = null;
        for (int answers = 0; answers < 2; answers++) {
            Future<InetAddress[]> done;
            try {
                done = race.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UnknownHostException("interrupted: " + host);
            }
            InetAddress[] addresses;
            try {
                addresses = done.get();
            } catch (ExecutionException e) {
                if (done == phone) phoneFailure = asUnknownHost(host, e.getCause());
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UnknownHostException("interrupted: " + host);
            }

            if (done == encrypted) {
                if (addresses != null) {
                    phone.cancel(true);
                    return addresses;
                }
            } else if (isSinkhole(addresses)) {
                logger.log("Phone DNS blocked " + host + ", waiting for encrypted DNS");
                sinkholed = addresses;
            } else {
                encrypted.cancel(true);
                return addresses;
            }
        }

        // Encrypted DNS had nothing: the phone's answer, even a blocked one, is all there is.
        if (sinkholed != null) return sinkholed;
        if (phoneFailure != null) throw phoneFailure;
        throw new UnknownHostException(host);
    }

    /** An ad blocking DNS answers blocked names with 0.0.0.0, ::, or a loopback address. */
    static boolean isSinkhole(InetAddress[] addresses) {
        if (addresses == null || addresses.length == 0) return true;
        for (InetAddress address : addresses) {
            if (!address.isAnyLocalAddress() && !address.isLoopbackAddress()) return false;
        }
        return true;
    }

    private static UnknownHostException asUnknownHost(String host, Throwable cause) {
        if (cause instanceof UnknownHostException) return (UnknownHostException) cause;
        UnknownHostException wrapped = new UnknownHostException(host + ": " + cause);
        wrapped.initCause(cause);
        return wrapped;
    }
}
