package dev.varun.forecast.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.varun.forecast.api.config.ForecastProperties;
import org.junit.jupiter.api.Test;

/**
 * The per-IP counters have to tell two visitors apart for one Pacific day. They never
 * have to say who either of them was, so they do not keep the addresses.
 */
class IpHasherTest {

    private static final String IP = "203.0.113.7";

    private static IpHasher keyedWith(String secret) {
        return new IpHasher(TestProps.ipSecret(secret));
    }

    @Test
    void theSameVisitorHashesToTheSameKey() {
        assertEquals(keyedWith("s").hash(IP), keyedWith("s").hash(IP));
    }

    @Test
    void differentVisitorsHashToDifferentKeys() {
        assertNotEquals(keyedWith("s").hash(IP), keyedWith("s").hash("203.0.113.8"));
    }

    @Test
    void theAddressIsNotInTheKey() {
        assertFalse(keyedWith("s").hash(IP).contains(IP));
    }

    /**
     * Why it is keyed rather than a plain digest: IPv4 is four billion addresses, so an
     * unkeyed hash of one is a few seconds of enumeration away from the address itself.
     * The secret is what makes the hash one-way in practice, which is also why it is
     * configured from outside rather than stored alongside the hashes.
     */
    @Test
    void aDifferentSecretGivesADifferentKeyForTheSameVisitor() {
        assertNotEquals(keyedWith("one").hash(IP), keyedWith("two").hash(IP));
    }

    /** Locally, where there is no Cloudflare, a missing secret warns and carries on. */
    @Test
    void aBlankSecretStillProducesAKeyWithNoEdgeInFront() {
        assertFalse(keyedWith("").hash(IP).isBlank());
    }

    /**
     * But not in deployment. The edge secret marks a real deployment, and there a
     * fallback key that ships in the image would leave every address recoverable by
     * anyone who reads the table — silently, which is the worst way for it to happen.
     */
    @Test
    void aBlankSecretBehindTheEdgeIsAStartupFailure() {
        ForecastProperties deployed = TestProps.deployedWithoutIpSecret();

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> new IpHasher(deployed));

        assertTrue(thrown.getMessage().contains("IP_HASH_SECRET"));
    }
}
