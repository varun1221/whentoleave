package dev.varun.forecast.api;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

/**
 * Phase 2. Phase 1 is a cron job and deliberately has no Spring in it — read config,
 * call HTTP, write files, exit. This module exists because there is now a real request
 * path to serve: a public endpoint that has to be cached, rate limited and quota capped.
 * That is what Spring is for, and introducing it here rather than there is the point.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ForecastApplication {

    public static void main(String[] args) {
        SpringApplication.run(ForecastApplication.class, args);
    }

    /** A bean rather than Instant.now() so day-boundary logic can be tested at a fixed time. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
