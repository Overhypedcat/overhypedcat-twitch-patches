package app.morphed.extension.twitch.dns;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphed.extension.twitch.adblock.Http;
import app.morphed.extension.twitch.adblock.Logger;

/**
 * Looks up host names over DNS-over-HTTPS (Cloudflare, then Google), so the Twitch app does not
 * depend on the phone's DNS. Answers are cached for their time to live.
 */
public final class DohResolver {
    /** Providers addressed by IP, so asking them needs no DNS itself. */
    static final List<String> PROVIDERS = Arrays.asList(
            "https://1.1.1.1/dns-query?name=%s&type=%s",
            "https://8.8.8.8/resolve?name=%s&type=%s");

    private static final long MIN_TTL_MS = 30_000;
    private static final long MAX_TTL_MS = 600_000;
    /** A provider that failed is skipped for this long, so a dead provider never slows every lookup. */
    private static final long PROVIDER_COOLDOWN_MS = 60_000;

    private static final Pattern ANSWER_OBJECT = Pattern.compile("\\{[^{}]*\\}");
    private static final Pattern TYPE = Pattern.compile("\"type\"\\s*:\\s*(\\d+)");
    private static final Pattern TTL = Pattern.compile("\"TTL\"\\s*:\\s*(\\d+)");
    private static final Pattern DATA = Pattern.compile("\"data\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9a-fA-F:]*:[0-9a-fA-F:.]*");

    private static final class Entry {
        final InetAddress[] addresses;
        final long expiresAt;

        Entry(InetAddress[] addresses, long expiresAt) {
            this.addresses = addresses;
            this.expiresAt = expiresAt;
        }
    }

    /** A parsed answer: addresses and the time to live in seconds. */
    static final class Answer {
        final List<String> addresses;
        final long ttlSeconds;

        Answer(List<String> addresses, long ttlSeconds) {
            this.addresses = addresses;
            this.ttlSeconds = ttlSeconds;
        }
    }

    private final Http http;
    private final Logger logger;
    private final LongSupplier clock;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Map<String, Long> failedProviders = new ConcurrentHashMap<>();

    public DohResolver(Http http, Logger logger, LongSupplier clock) {
        this.http = http;
        this.logger = logger;
        this.clock = clock;
    }

    /** Whether {@code host} is a public host name; IP addresses and local names stay with the phone. */
    public static boolean canResolve(String host) {
        if (host == null) return false;
        String name = host.toLowerCase(Locale.ROOT);
        if (name.endsWith(".")) name = name.substring(0, name.length() - 1);
        if (IPV4.matcher(name).matches() || name.contains(":")) return false;
        return name.contains(".") && !name.endsWith(".local") && !name.equals("localhost");
    }

    /** The addresses of {@code host}, or null to let the phone's DNS answer instead. */
    public InetAddress[] resolve(String host) {
        String name = host.toLowerCase(Locale.ROOT);
        long now = clock.getAsLong();
        Entry cached = cache.get(name);
        if (cached != null && cached.expiresAt > now) return cached.addresses;

        // IPv4 first: OkHttp tries addresses in order and many networks have no working IPv6.
        for (String type : new String[]{"A", "AAAA"}) {
            Answer answer = query(name, type);
            if (answer == null) return null;
            if (answer.addresses.isEmpty()) continue;
            InetAddress[] addresses = toAddresses(host, answer.addresses);
            if (addresses == null) return null;
            long ttl = Math.max(MIN_TTL_MS, Math.min(MAX_TTL_MS, answer.ttlSeconds * 1000));
            cache.put(name, new Entry(addresses, now + ttl));
            return addresses;
        }
        return null;
    }

    /** The answer of the first provider that responds, or null when none does. */
    private Answer query(String name, String type) {
        for (String provider : PROVIDERS) {
            Long failedAt = failedProviders.get(provider);
            if (failedAt != null && clock.getAsLong() - failedAt < PROVIDER_COOLDOWN_MS) continue;
            long start = clock.getAsLong();
            try {
                Http.Response response = http.get(String.format(provider, name, type),
                        Collections.singletonMap("Accept", "application/dns-json"));
                Answer answer = response.isOk() ? parse(response.body, type) : null;
                if (answer != null) {
                    logger.log("DNS " + name + " " + type + " -> " + answer.addresses + " in " + (clock.getAsLong() - start) + "ms");
                    return answer;
                }
                logger.log("DNS " + name + " " + type + ": provider answered " + response.status);
            } catch (IOException e) {
                logger.log("DNS " + name + " " + type + " failed after " + (clock.getAsLong() - start) + "ms: " + e);
            }
            failedProviders.put(provider, clock.getAsLong());
        }
        return null;
    }

    /** Parses a DNS JSON answer; the addresses list is empty when the name has no record of that type. */
    static Answer parse(String json, String type) {
        if (json == null || !json.contains("\"Status\"")) return null;
        Matcher status = Pattern.compile("\"Status\"\\s*:\\s*(\\d+)").matcher(json);
        if (!status.find()) return null;
        int code = Integer.parseInt(status.group(1));
        // 0 is success, 3 is "no such name": both are definite answers.
        if (code != 0 && code != 3) return null;

        int wanted = "AAAA".equals(type) ? 28 : 1;
        List<String> addresses = new ArrayList<>();
        long ttl = Long.MAX_VALUE;
        int answerStart = json.indexOf("\"Answer\"");
        if (answerStart >= 0) {
            Matcher object = ANSWER_OBJECT.matcher(json);
            object.region(answerStart, json.length());
            while (object.find()) {
                String record = object.group();
                Matcher recordType = TYPE.matcher(record);
                Matcher data = DATA.matcher(record);
                if (!recordType.find() || !data.find()) continue;
                if (Integer.parseInt(recordType.group(1)) != wanted) continue;
                addresses.add(data.group(1));
                Matcher recordTtl = TTL.matcher(record);
                if (recordTtl.find()) ttl = Math.min(ttl, Long.parseLong(recordTtl.group(1)));
            }
        }
        return new Answer(addresses, ttl == Long.MAX_VALUE ? 60 : ttl);
    }

    private static InetAddress[] toAddresses(String host, List<String> literals) {
        List<InetAddress> addresses = new ArrayList<>();
        for (String literal : literals) {
            // Only IP literals are accepted, so the conversion never triggers a DNS lookup itself.
            if (!IPV4.matcher(literal).matches() && !IPV6.matcher(literal).matches()) continue;
            try {
                addresses.add(InetAddress.getByAddress(host, InetAddress.getByName(literal).getAddress()));
            } catch (UnknownHostException ignored) {
                // Skip a malformed address.
            }
        }
        return addresses.isEmpty() ? null : addresses.toArray(new InetAddress[0]);
    }
}
