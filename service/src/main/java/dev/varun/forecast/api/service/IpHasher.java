package dev.varun.forecast.api.service;

import dev.varun.forecast.api.config.ForecastProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns a visitor's address into the key their daily counters are stored under.
 *
 * <p>{@link DailyIpLimiter} has to tell two visitors apart for one Pacific day. It never
 * has to turn a row back into an address, so it does not keep one.
 *
 * <p>Keyed, not merely hashed. IPv4 is about four billion addresses, which is small
 * enough to enumerate against a plain digest in seconds — an unkeyed hash of an address
 * is a reversible encoding of it, not a replacement. The HMAC key is a server secret
 * held outside the database, so a copy of the table on its own says nothing about who
 * visited.
 */
@Component
public class IpHasher {

    private static final Logger log = LoggerFactory.getLogger(IpHasher.class);

    private static final String ALGORITHM = "HmacSHA256";

    /**
     * How much of the digest is kept: 128 bits. Far more than collision resistance needs
     * for one day of visitors, and half the index width of carrying the whole thing.
     */
    private static final int DIGEST_BYTES = 16;

    /** Stands in for an unset secret, which is a local-development state. */
    private static final String UNCONFIGURED = "forecast-development-key";

    private final SecretKeySpec key;

    public IpHasher(ForecastProperties props) {
        if (!props.privacy().keyed()) {
            // The edge secret is the flag for "this is a real deployment": it is blank
            // locally and set wherever Cloudflare fronts the service. Falling back to a
            // key that ships in the image is a local convenience, and carrying that into
            // deployment would leave the addresses recoverable by anyone who reads the
            // table — the exact thing the hashing is for. So there it is a startup
            // failure, which is loud, rather than a warning nobody reads.
            if (props.edge().enforced()) {
                throw new IllegalStateException("forecast.privacy.ip-secret must be set "
                        + "when forecast.edge.origin-secret is. Set IP_HASH_SECRET.");
            }
            log.warn("forecast.privacy.ip-secret is not set: per-IP counters are keyed "
                    + "by a well-known value, so the addresses behind them could be "
                    + "recovered by anyone who reads the table. Set IP_HASH_SECRET.");
        }
        String secret = props.privacy().keyed() ? props.privacy().ipSecret() : UNCONFIGURED;
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public String hash(String ip) {
        try {
            // A Mac is stateful, so each call gets its own rather than sharing one
            // across the request threads that arrive here concurrently.
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] digest = mac.doFinal(ip.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(Arrays.copyOf(digest, DIGEST_BYTES));
        } catch (GeneralSecurityException e) {
            // Every JVM ships HmacSHA256; if this one does not, failing the request is
            // the only honest answer, because the alternative is storing the address.
            throw new IllegalStateException(ALGORITHM + " is unavailable", e);
        }
    }
}
