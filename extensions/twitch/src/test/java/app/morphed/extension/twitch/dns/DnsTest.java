package app.morphed.extension.twitch.dns;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphed.extension.twitch.adblock.Http;
import app.morphed.extension.twitch.adblock.Logger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DnsTest {
    private static String answer(int type, String... addresses) {
        StringBuilder records = new StringBuilder();
        for (String address : addresses) {
            if (records.length() > 0) records.append(',');
            records.append("{\"name\":\"x.\",\"type\":").append(type).append(",\"TTL\":120,\"data\":\"").append(address).append("\"}");
        }
        return "{\"Status\":0,\"TC\":false,\"Question\":[{\"name\":\"x.\",\"type\":" + type + "}],\"Answer\":[" + records + "]}";
    }

    /** Answers by URL substring; throws for anything unknown, like an unreachable provider. */
    private static final class FakeHttp implements Http {
        final Map<String, String> answers = Collections.synchronizedMap(new LinkedHashMap<>());
        // Lookups run on several threads.
        final List<String> requests = Collections.synchronizedList(new ArrayList<>());

        @Override
        public Response get(String url, Map<String, String> headers) throws IOException {
            requests.add(url);
            for (Map.Entry<String, String> entry : answers.entrySet()) {
                if (url.contains(entry.getKey())) return new Response(200, entry.getValue());
            }
            throw new IOException("unreachable");
        }

        @Override
        public Response post(String url, Map<String, String> headers, String body) throws IOException {
            throw new IOException("unused");
        }
    }

    private long now = 1_000_000;
    private final FakeHttp http = new FakeHttp();
    private final DohResolver doh = new DohResolver(http, Logger.NONE, () -> now);

    @Test
    public void blocksTwitchAdAndTrackingServers() {
        assertTrue(HostBlocklist.isBlocked("edge.ads.twitch.tv"));
        assertTrue(HostBlocklist.isBlocked("spade.twitch.tv"));
        assertTrue(HostBlocklist.isBlocked("player.stats.live-video.net"));
        assertTrue(HostBlocklist.isBlocked("sb.scorecardresearch.com"));
        assertTrue(HostBlocklist.isBlocked("Pubads.G.DoubleClick.net."));
    }

    @Test
    public void leavesTwitchItselfAlone() {
        assertFalse(HostBlocklist.isBlocked("gql.twitch.tv"));
        assertFalse(HostBlocklist.isBlocked("usher.ttvnw.net"));
        assertFalse(HostBlocklist.isBlocked("euc11.playlist.ttvnw.net"));
        assertFalse(HostBlocklist.isBlocked("static-cdn.jtvnw.net"));
        assertFalse(HostBlocklist.isBlocked("irc-ws.chat.twitch.tv"));
        assertFalse(HostBlocklist.isBlocked("api2.branch.io"));
        assertFalse(HostBlocklist.isBlocked("downloads.twitch.tv"));
    }

    @Test
    public void onlyPublicNamesGoToEncryptedDns() {
        assertTrue(DohResolver.canResolve("gql.twitch.tv"));
        assertFalse(DohResolver.canResolve("1.1.1.1"));
        assertFalse(DohResolver.canResolve("2606:4700::1111"));
        assertFalse(DohResolver.canResolve("localhost"));
        assertFalse(DohResolver.canResolve("printer.local"));
    }

    @Test
    public void parsesAddressesSkippingCnames() {
        String json = "{\"Status\":0,\"Answer\":[{\"name\":\"a.\",\"type\":5,\"TTL\":300,\"data\":\"b.example.\"},"
                + "{\"name\":\"b.\",\"type\":1,\"TTL\":60,\"data\":\"1.2.3.4\"},{\"name\":\"b.\",\"type\":1,\"TTL\":90,\"data\":\"5.6.7.8\"}]}";
        DohResolver.Answer answer = DohResolver.parse(json, "A");

        assertEquals(Arrays.asList("1.2.3.4", "5.6.7.8"), answer.addresses);
        assertEquals(60, answer.ttlSeconds);
    }

    @Test
    public void noSuchNameIsAnEmptyAnswerAndGarbageIsNone() {
        assertTrue(DohResolver.parse("{\"Status\":3,\"Question\":[]}", "A").addresses.isEmpty());
        assertNull(DohResolver.parse("{\"Status\":2}", "A"));
        assertNull(DohResolver.parse("<html>", "A"));
    }

    @Test
    public void resolvesIpv4AndCachesIt() throws Exception {
        http.answers.put("1.1.1.1/dns-query?name=gql.twitch.tv&type=A", answer(1, "151.101.2.167"));

        InetAddress[] first = doh.resolve("gql.twitch.tv");
        InetAddress[] second = doh.resolve("gql.twitch.tv");

        assertEquals("151.101.2.167", first[0].getHostAddress());
        assertEquals("gql.twitch.tv", first[0].getHostName());
        assertArrayEquals(first, second);
        assertEquals(1, http.requests.size());
    }

    @Test
    public void fallsBackToIpv6WhenThereIsNoIpv4() {
        http.answers.put("type=AAAA", answer(28, "2001:db8::1"));
        http.answers.put("type=A", answer(1));

        InetAddress[] addresses = doh.resolve("v6only.example.com");

        assertEquals(1, addresses.length);
        assertTrue(addresses[0].getHostAddress().startsWith("2001:db8"));
    }

    @Test
    public void triesTheSecondProviderAndSkipsADeadOneForAWhile() {
        http.answers.put("8.8.8.8/resolve?name=gql.twitch.tv", answer(1, "151.101.2.167"));
        http.answers.put("8.8.8.8/resolve?name=usher.ttvnw.net", answer(1, "52.223.1.1"));

        assertEquals("151.101.2.167", doh.resolve("gql.twitch.tv")[0].getHostAddress());
        int requests = http.requests.size();
        doh.resolve("usher.ttvnw.net");

        // Cloudflare failed a moment ago, so only Google is asked.
        assertEquals(requests + 1, http.requests.size());
        assertTrue(http.requests.get(requests).contains("8.8.8.8"));
    }

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @After
    public void tearDown() {
        executor.shutdownNow();
    }

    private static InetAddress address(int a, int b, int c, int d) throws UnknownHostException {
        return InetAddress.getByAddress(new byte[]{(byte) a, (byte) b, (byte) c, (byte) d});
    }

    /** A phone DNS that answers after {@code delayMs} with {@code answer}, or fails when it is null. */
    private AppDns appDns(long delayMs, InetAddress answer) {
        return new AppDns(host -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                throw new UnknownHostException("cancelled");
            }
            if (answer == null) throw new UnknownHostException(host);
            return new InetAddress[]{answer};
        }, doh, executor, Logger.NONE);
    }

    @Test
    public void refusesBlockedServersWithoutAnyLookup() {
        try {
            appDns(0, null).lookup("edge.ads.twitch.tv");
            fail();
        } catch (UnknownHostException expected) {
            assertTrue(expected.getMessage().contains("Blocked"));
        }
        assertTrue(http.requests.isEmpty());
    }

    @Test
    public void slowPhoneDnsLosesToEncryptedDns() throws Exception {
        http.answers.put("name=gql.twitch.tv", answer(1, "151.101.2.167"));

        assertEquals("151.101.2.167", appDns(5_000, address(10, 0, 0, 1)).lookup("gql.twitch.tv")[0].getHostAddress());
    }

    @Test
    public void unreachableEncryptedDnsDoesNotDelayThePhoneAnswer() throws Exception {
        long start = System.nanoTime();
        InetAddress[] addresses = appDns(0, address(10, 0, 0, 1)).lookup("gql.twitch.tv");

        assertEquals("10.0.0.1", addresses[0].getHostAddress());
        assertTrue(System.nanoTime() - start < 1_000_000_000L);
    }

    @Test
    public void blockedAnswerFromPhoneDnsWaitsForEncryptedDns() throws Exception {
        http.answers.put("name=8a2de3.rufio.hls.live-video.net", answer(1, "52.58.77.213"));

        InetAddress[] addresses = appDns(0, InetAddress.getByName("::")).lookup("8a2de3.rufio.hls.live-video.net");

        assertEquals("52.58.77.213", addresses[0].getHostAddress());
    }

    @Test
    public void phoneFailureFallsToEncryptedDnsAndBothFailingFails() throws Exception {
        http.answers.put("name=gql.twitch.tv", answer(1, "151.101.2.167"));
        assertEquals("151.101.2.167", appDns(0, null).lookup("gql.twitch.tv")[0].getHostAddress());

        try {
            appDns(0, null).lookup("nowhere.example.com");
            fail();
        } catch (UnknownHostException expected) {
            assertTrue(expected.getMessage().contains("nowhere.example.com"));
        }
    }

    @Test
    public void ipAddressesStayWithThePhone() throws Exception {
        assertEquals("10.0.0.1", appDns(0, address(10, 0, 0, 1)).lookup("10.0.0.1")[0].getHostAddress());
        assertTrue(http.requests.isEmpty());
    }

    @Test
    public void recognisesBlockedAnswers() throws Exception {
        assertTrue(AppDns.isSinkhole(new InetAddress[]{InetAddress.getByName("0.0.0.0")}));
        assertTrue(AppDns.isSinkhole(new InetAddress[]{InetAddress.getByName("::")}));
        assertTrue(AppDns.isSinkhole(new InetAddress[]{InetAddress.getByName("127.0.0.1")}));
        assertFalse(AppDns.isSinkhole(new InetAddress[]{address(151, 101, 2, 167)}));
    }
}
