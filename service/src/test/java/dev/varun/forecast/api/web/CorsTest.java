package dev.varun.forecast.api.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.varun.forecast.api.DatabaseTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The site and the API are different origins — whentoleave.me calls api.whentoleave.me —
 * so without these headers the browser refuses every answer and the lookup panel reports
 * an unreachable service.
 */
@AutoConfigureMockMvc
class CorsTest extends DatabaseTest {

    private static final String SITE = "https://whentoleave.me";

    @Autowired private MockMvc mvc;

    /** A JSON POST is not a simple request, so the browser asks first. */
    @Test
    void theSiteMayPostALookup() throws Exception {
        mvc.perform(options("/api/lookup")
                        .header("Origin", SITE)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", SITE));
    }

    @Test
    void theWwwSiteIsAllowedToo() throws Exception {
        mvc.perform(get("/api/routes").header("Origin", "https://www.whentoleave.me"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin",
                        "https://www.whentoleave.me"));
    }

    /**
     * Another site's page cannot read these answers. That is not the security boundary
     * — the per-IP limits are — but it stops a stranger's page spending a visitor's
     * lookups from inside their browser.
     */
    @Test
    void anotherSiteIsRefused() throws Exception {
        mvc.perform(options("/api/lookup")
                        .header("Origin", "https://example.com")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void aRequestWithNoOriginIsUnaffected() throws Exception {
        mvc.perform(get("/api/routes"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
