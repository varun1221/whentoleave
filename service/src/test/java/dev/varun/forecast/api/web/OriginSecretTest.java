package dev.varun.forecast.api.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.varun.forecast.api.DatabaseTest;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Per-IP limits key on {@code CF-Connecting-IP}, which is only trustworthy on a request
 * that came through Cloudflare. Anyone calling the {@code *.run.app} hostname directly
 * could otherwise set it to anything and get a fresh budget per request. Cloudflare adds
 * a secret header on the way through; without it, the API refuses.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "forecast.edge.origin-secret=test-origin-secret")
class OriginSecretTest extends DatabaseTest {

    @Autowired private MockMvc mvc;

    @Test
    void anApiRequestWithoutTheSecretIsRefused() throws Exception {
        mvc.perform(get("/api/quota").header("CF-Connecting-IP", "203.0.113.1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void anApiRequestWithTheWrongSecretIsRefused() throws Exception {
        mvc.perform(get("/api/quota").header("X-Origin-Secret", "guess"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anApiRequestWithTheSecretIsServed() throws Exception {
        mvc.perform(get("/api/quota").header("X-Origin-Secret", "test-origin-secret"))
                .andExpect(status().isOk());
    }

    /**
     * Spring matches controllers on the decoded, parameter-stripped path. The first two
     * reached the API without the secret when the filter checked the raw URI for /api/.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/%61pi/quota", "/api;x=1/quota", "/API/quota", "//api/quota"})
    void pathVariantsThatReachTheApiStillNeedTheSecret(String path) throws Exception {
        mvc.perform(get(URI.create(path)))
                .andExpect(status().isForbidden());
    }

    /** Cloud Run probes the container directly, not through Cloudflare. */
    @ParameterizedTest
    @ValueSource(strings = {
        "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"})
    void healthProbesDoNotNeedTheSecret(String path) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().isOk());
    }
}
