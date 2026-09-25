package dev.varun.forecast.api.config;

import jakarta.servlet.http.HttpServletRequest;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * The caller's address, for per-IP limiting.
 *
 * <p>Cloudflare sits in front of this service, so {@code getRemoteAddr()} is Cloudflare's
 * edge rather than the visitor — keying a limit on it would put every visitor in one
 * bucket. {@code CF-Connecting-IP} is the real client, and Cloudflare overwrites it on
 * the way through. That only holds for requests that actually came through Cloudflare:
 * a direct call to the Cloud Run hostname can set the header to anything, which is what
 * {@link OriginSecretFilter} is for.
 *
 * <p>An IPv6 address is keyed by its /64, not in full. One connection is routinely handed
 * a whole /64 and can use any address in it, so a full-address key would give one person
 * a fresh budget per request. IPv4 addresses, including IPv4-mapped IPv6 ones, are keyed
 * as the address itself.
 *
 * <p>Falling back to {@code getRemoteAddr()} matters for local development, where there
 * is no Cloudflare in the path at all.
 */
public final class ClientIp {

    private static final String CLOUDFLARE_HEADER = "CF-Connecting-IP";

    /**
     * Only a string that can be nothing but an IPv6 literal is parsed. A colon can never
     * appear in a hostname, and with only hex digits, colons and dots there is nothing
     * {@link InetAddress#getByName} could mistake for a name and resolve.
     */
    private static final Pattern IPV6_LITERAL =
            Pattern.compile("[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*");

    private static final int PREFIX_BYTES = 8;

    public static String of(HttpServletRequest request) {
        String header = request.getHeader(CLOUDFLARE_HEADER);
        if (header != null && !header.isBlank()) {
            return key(header.trim());
        }
        return key(request.getRemoteAddr());
    }

    private static String key(String address) {
        if (address == null || !IPV6_LITERAL.matcher(address).matches()) {
            return address;
        }
        InetAddress parsed;
        try {
            parsed = InetAddress.getByName(address);
        } catch (UnknownHostException e) {
            return address;
        }
        if (!(parsed instanceof Inet6Address)) {
            // ::ffff:a.b.c.d parses as the IPv4 address it wraps, and is that visitor.
            return parsed.getHostAddress();
        }
        byte[] prefix = Arrays.copyOf(parsed.getAddress(), 16);
        Arrays.fill(prefix, PREFIX_BYTES, prefix.length, (byte) 0);
        try {
            return InetAddress.getByAddress(prefix).getHostAddress() + "/64";
        } catch (UnknownHostException e) {
            throw new IllegalStateException("16 bytes is always an IPv6 address", e);
        }
    }

    private ClientIp() {}
}
