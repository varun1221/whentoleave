package dev.varun.forecast.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Lets the site read the API's answers from a browser.
 *
 * <p>whentoleave.me and api.whentoleave.me are different origins, so the browser only
 * hands the page a response that names the page's origin. Only what the frontend sends
 * is allowed: GET and POST, and the one header a JSON body needs. No credentials, since
 * the API has no cookies or sessions to carry.
 *
 * <p>CORS decides what a browser page may read, not who may call. The per-IP limits and
 * {@link OriginSecretFilter} are the guards; this only keeps a stranger's page from
 * spending a visitor's lookups from inside that visitor's browser.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final ForecastProperties.Edge edge;

    public CorsConfig(ForecastProperties props) {
        this.edge = props.edge();
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (edge.allowedOrigins() == null || edge.allowedOrigins().isEmpty()) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(edge.allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST")
                .allowedHeaders("Content-Type")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
