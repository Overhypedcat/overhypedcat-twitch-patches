package app.morphed.extension.twitch.dns;

import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        final Map<String, String> answers = new LinkedHashMap<>();
        final List<String> requests = new ArrayList<>();

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

    @Test
    public void appDnsBlocksUsesEncryptedDnsAndFallsBackToThePhone() throws Exception {
        List<String> phoneLookups = new ArrayList<>();
        InetAddress phoneAddress = InetAddress.getByAddress("x", new byte[]{10, 0, 0, 1});
        AppDns dns = new AppDns(host -> {
            phoneLookups.add(host);
            return new InetAddress[]{phoneAddress};
        }, doh, Logger.NONE);
        http.answers.put("name=gql.twitch.tv", answer(1, "151.101.2.167"));

        try {
            dns.lookup("edge.ads.twitch.tv");
            fail();
        } catch (UnknownHostException expected) {
            assertTrue(expected.getMessage().contains("Blocked"));
        }
        assertEquals("151.101.2.167", dns.lookup("gql.twitch.tv")[0].getHostAddress());
        assertEquals(phoneAddress, dns.lookup("unreachable.example.com")[0]);
        assertEquals(phoneAddress, dns.lookup("10.0.0.1")[0]);
        assertEquals(Arrays.asList("unreachable.example.com", "10.0.0.1"), phoneLookups);
    }
}
