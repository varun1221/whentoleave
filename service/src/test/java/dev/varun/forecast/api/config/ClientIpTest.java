package dev.varun.forecast.api.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The key per-IP limits count against. An IPv4 address is one visitor, but an IPv6
 * connection is routinely handed a whole /64 — more addresses than the limits could ever
 * count — so keying on the full address hands one person a fresh budget per request.
 */
class ClientIpTest {

    private static String keyFor(String header) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("CF-Connecting-IP", header);
        return ClientIp.of(request);
    }

    @Test
    void anIpv4AddressIsItsOwnKey() {
        assertEquals("203.0.113.7", keyFor("203.0.113.7"));
    }

    @Test
    void everyAddressInOneIpv6Slash64SharesAKey() {
        assertEquals(keyFor("2001:db8:1:2::1"), keyFor("2001:db8:1:2:ffff:abcd:1234:9"));
    }

    @Test
    void differentIpv6Slash64sAreDifferentVisitors() {
        assertNotEquals(keyFor("2001:db8:1:2::1"), keyFor("2001:db8:1:3::1"));
    }

    /** Spelling an address differently must not buy a second budget. */
    @Test
    void compressedAndExpandedIpv6SpellingsShareAKey() {
        assertEquals(keyFor("2001:db8:0:0::1"),
                keyFor("2001:0DB8:0000:0000:0000:0000:0000:0001"));
    }

    @Test
    void anIpv4MappedAddressIsTheIpv4Visitor() {
        assertEquals(keyFor("198.51.100.4"), keyFor("::ffff:198.51.100.4"));
    }

    /** Only literals are parsed. Anything else is kept as-is and never resolved. */
    @Test
    void somethingThatIsNotAnAddressIsKeptVerbatim() {
        assertEquals("not-an-address", keyFor("not-an-address"));
        assertEquals("zz:yy", keyFor("zz:yy"));
    }

    @Test
    void withoutCloudflareTheSocketAddressIsKeyedTheSameWay() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("2001:db8:1:2::1");

        assertEquals(keyFor("2001:db8:1:2::99"), ClientIp.of(request));
    }
}
