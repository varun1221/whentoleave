package dev.varun.forecast.api.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.varun.forecast.api.DatabaseTest;
import dev.varun.forecast.api.domain.Corridor;
import dev.varun.forecast.api.domain.Sample;
import dev.varun.forecast.api.repo.CorridorRepository;
import dev.varun.forecast.api.repo.SampleRepository;
import dev.varun.forecast.api.service.DailyIpLimiter;
import dev.varun.forecast.api.service.QuotaDay;
import dev.varun.forecast.api.service.QuotaService;
import java.time.DayOfWeek;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The HTTP contract the frontend codes against. */
@AutoConfigureMockMvc
class ApiTest extends DatabaseTest {

    @Autowired private MockMvc mvc;
    @Autowired private CorridorRepository corridors;
    @Autowired private SampleRepository samples;
    @Autowired private DailyIpLimiter perIp;
    @Autowired private QuotaService quotas;
    @Autowired private QuotaDay day;

    private static final String UNCACHED_LOOKUP =
            "{\"origin\":\"37.91,-122.91\",\"dest\":\"38.91,-121.91\"}";

    private Corridor seedSjsu() {
        Corridor corridor = corridors.save(new Corridor("sjsu-sf", "37.3352,-121.8811",
                "37.7946,-122.3999", "SJSU to SF", true));
        samples.save(new Sample(corridor.getId(), DayOfWeek.WEDNESDAY, 8, 5295, 87_094,
                Instant.now()));
        return corridor;
    }

    @Test
    void healthIsUpForCloudRunProbes() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void listsSeededCorridorsWithTheirSampleTotals() throws Exception {
        seedSjsu();

        mvc.perform(get("/api/routes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("sjsu-sf"))
                .andExpect(jsonPath("$[0].name").value("SJSU to SF"))
                .andExpect(jsonPath("$[0].sampleCount").value(1))
                .andExpect(jsonPath("$[0].lastSampledAt").exists());
    }

    @Test
    void anEmptyDatabaseListsNoRoutesRatherThanFailing() throws Exception {
        mvc.perform(get("/api/routes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(0)));
    }

    @Test
    void returnsTheFullGridForASeededCorridor() throws Exception {
        seedSjsu();

        mvc.perform(get("/api/routes/sjsu-sf/forecast"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("sjsu-sf"))
                .andExpect(jsonPath("$.partial").value(false))
                .andExpect(jsonPath("$.notice").doesNotExist())
                .andExpect(jsonPath("$.buckets.MONDAY", org.hamcrest.Matchers.hasSize(13)))
                .andExpect(jsonPath("$.buckets.WEDNESDAY[2].slotHour").value(8))
                .andExpect(jsonPath("$.buckets.WEDNESDAY[2].medianSeconds").value(5295))
                .andExpect(jsonPath("$.buckets.WEDNESDAY[2].n").value(1));
    }

    /** An empty bucket must be null, never zero — a zero renders as an instant trip. */
    @Test
    void anEmptyBucketSerialisesAsNull() throws Exception {
        seedSjsu();

        mvc.perform(get("/api/routes/sjsu-sf/forecast"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buckets.MONDAY[0].medianSeconds").isEmpty())
                .andExpect(jsonPath("$.buckets.MONDAY[0].n").value(0));
    }

    @Test
    void anUnknownCorridorIsNotFound() throws Exception {
        mvc.perform(get("/api/routes/nope/forecast"))
                .andExpect(status().isNotFound());
    }

    @Test
    void reportsBothBudgetsAndTheSwitch() throws Exception {
        mvc.perform(get("/api/quota"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.yourRemaining").value(5))
                .andExpect(jsonPath("$.yourDailyLimit").value(5))
                .andExpect(jsonPath("$.yourSearchesRemaining").value(20))
                .andExpect(jsonPath("$.yourSearchDailyLimit").value(20))
                .andExpect(jsonPath("$.globalDailyCeiling").value(150))
                .andExpect(jsonPath("$.lookupsPaused").value(false))
                .andExpect(jsonPath("$.lookupsAvailable").value(true));
    }

    /** §10.4: the UI says when the limit lifts, so the response has to. */
    @Test
    void reportsWhenTheBudgetsRefill() throws Exception {
        mvc.perform(get("/api/quota"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resetsAt").value(day.resetsAt().toString()));
    }

    @Test
    void aRateLimitedLookupIsA429WithTheLimitAndResetTime() throws Exception {
        String ip = "198.51.100.30";
        for (int i = 0; i < 5; i++) {
            perIp.tryConsume(DailyIpLimiter.Budget.LOOKUP, ip);
        }

        mvc.perform(post("/api/lookup")
                        .header("CF-Connecting-IP", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(UNCACHED_LOOKUP))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("rate_limited"))
                .andExpect(jsonPath("$.dailyLimit").value(5))
                .andExpect(jsonPath("$.resetsAt").value(day.resetsAt().toString()));
    }

    @Test
    void anExhaustedGlobalBudgetIsA429WithTheResetTime() throws Exception {
        quotas.reserve(150);

        mvc.perform(post("/api/lookup")
                        .header("CF-Connecting-IP", "198.51.100.31")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(UNCACHED_LOOKUP))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("quota_exhausted"))
                .andExpect(jsonPath("$.resetsAt").value(day.resetsAt().toString()));
    }

    /**
     * A partially cached corridor is served rather than refused, and the banner the UI
     * renders travels as {@code notice} on a 200. Same vocabulary as {@code error} on a
     * 4xx, so the frontend switches on one set of strings.
     */
    @Test
    void aDegradedLookupIsA200CarryingTheReasonAsANotice() throws Exception {
        String ip = "198.51.100.32";
        Corridor corridor = corridors.save(
                new Corridor(null, "37.91,-122.91", "38.91,-121.91", null, false));
        samples.save(new Sample(corridor.getId(), DayOfWeek.MONDAY, 6, 1800, 40_000,
                Instant.now()));
        jdbc.update("UPDATE service_setting SET value = 'false' "
                + "WHERE key = 'lookups_enabled'");

        mvc.perform(post("/api/lookup")
                        .header("CF-Connecting-IP", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(UNCACHED_LOOKUP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notice").value("lookups_paused"))
                .andExpect(jsonPath("$.sampleCount").value(1));
    }

    @Test
    void reportsThePausedStateWhenTheSwitchIsThrown() throws Exception {
        jdbc.update("UPDATE service_setting SET value = 'false' "
                + "WHERE key = 'lookups_enabled'");

        mvc.perform(get("/api/quota"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lookupsPaused").value(true))
                .andExpect(jsonPath("$.lookupsAvailable").value(false));
    }

    /**
     * Cloudflare sits in front, so getRemoteAddr is the edge. Keying on it would put
     * every visitor in one bucket.
     */
    @Test
    void perIpBudgetsAreKeyedOnTheCloudflareHeader() throws Exception {
        mvc.perform(get("/api/quota").header("CF-Connecting-IP", "198.51.100.1"))
                .andExpect(jsonPath("$.yourRemaining").value(5));
        mvc.perform(get("/api/quota").header("CF-Connecting-IP", "198.51.100.2"))
                .andExpect(jsonPath("$.yourRemaining").value(5));
    }

    @Test
    void rejectsALookupWithAMalformedCoordinate() throws Exception {
        mvc.perform(post("/api/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"origin\":\"nowhere\",\"dest\":\"37.7946,-122.3999\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("origin")));
    }

    @Test
    void rejectsALookupWithAMissingField() throws Exception {
        mvc.perform(post("/api/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"origin\":\"37.3352,-121.8811\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void rejectsALookupBetweenOnePlaceAndItself() throws Exception {
        mvc.perform(post("/api/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"origin\":\"37.3352,-121.8811\","
                                + "\"dest\":\"37.33520, -121.88110\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("same place")));
    }

    @Test
    void servesALookupOnASeededCorridorFromCommittedData() throws Exception {
        seedSjsu();

        mvc.perform(post("/api/lookup")
                        .header("CF-Connecting-IP", "198.51.100.9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"origin\":\"37.3352, -121.8811\","
                                + "\"dest\":\"37.7946, -122.3999\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("sjsu-sf"))
                .andExpect(jsonPath("$.partial").value(false));

        mvc.perform(get("/api/quota").header("CF-Connecting-IP", "198.51.100.9"))
                .andExpect(jsonPath("$.yourRemaining").value(5));
    }

    @Test
    void aShortAutocompleteQueryReturnsAnEmptyListWithoutCallingOut() throws Exception {
        mvc.perform(get("/api/places/autocomplete").param("q", "sj"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(0)));
    }

    /** No key is configured in tests, so a real query degrades to an empty dropdown. */
    @Test
    void anAutocompleteFailureDegradesToAnEmptyList() throws Exception {
        mvc.perform(get("/api/places/autocomplete")
                        .header("CF-Connecting-IP", "198.51.100.20")
                        .param("q", "palo alto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(0)));
    }

    @Test
    void autocompleteRequiresAQueryParameter() throws Exception {
        mvc.perform(get("/api/places/autocomplete"))
                .andExpect(status().isBadRequest());
    }
}
