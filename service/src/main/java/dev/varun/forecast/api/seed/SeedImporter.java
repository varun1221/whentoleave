package dev.varun.forecast.api.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.varun.forecast.api.domain.Corridor;
import dev.varun.forecast.api.domain.Sample;
import dev.varun.forecast.api.repo.CorridorRepository;
import dev.varun.forecast.api.repo.SampleRepository;
import dev.varun.forecast.api.service.Coordinates;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-off import of the Phase 1 JSONL history into Postgres.
 *
 * <p>Deliberately a command rather than something that runs at startup: the service must
 * not depend on files in the repo at request time, and re-reading 1,365 rows on every
 * cold start would be pure waste. Run it with:
 *
 * <pre>./gradlew :service:bootRun --args='--import-seed'</pre>
 *
 * <p>Idempotent. Re-running imports only sweeps that are not already in the table, so it
 * is safe to run after each weekly sweep without duplicating history.
 */
@Component
public class SeedImporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedImporter.class);

    /** The flag that turns this from a no-op into an import. */
    private static final String FLAG = "import-seed";

    private final CorridorRepository corridors;
    private final SampleRepository samples;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path routesConfig;
    private final Path samplesDir;

    public SeedImporter(CorridorRepository corridors, SampleRepository samples,
            @Value("${forecast.seed.routes:config/routes.json}") Path routesConfig,
            @Value("${forecast.seed.samples:data/samples}") Path samplesDir) {
        this.corridors = corridors;
        this.samples = samples;
        this.routesConfig = routesConfig;
        this.samplesDir = samplesDir;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.containsOption(FLAG)) {
            return;
        }
        if (!Files.exists(routesConfig)) {
            log.error("No route config at {} — run this from the repository root.",
                    routesConfig.toAbsolutePath());
            return;
        }
        int corridorsSeen = 0;
        int inserted = 0;
        int skipped = 0;

        for (JsonNode route : mapper.readTree(Files.readString(routesConfig))) {
            String slug = route.path("id").asText();
            Corridor corridor = upsertCorridor(
                    slug,
                    Coordinates.normalise(route.path("origin").asText()),
                    Coordinates.normalise(route.path("dest").asText()),
                    route.path("name").asText());
            corridorsSeen++;

            Path jsonl = samplesDir.resolve(slug + ".jsonl");
            if (!Files.exists(jsonl)) {
                log.warn("No samples file for {} — skipping.", slug);
                continue;
            }
            int[] counts = importSamples(corridor, jsonl);
            inserted += counts[0];
            skipped += counts[1];
        }

        log.info("seed import complete: corridors={} inserted={} alreadyPresent={}",
                corridorsSeen, inserted, skipped);
    }

    @Transactional
    Corridor upsertCorridor(String slug, String origin, String dest, String label) {
        return corridors.findBySlug(slug)
                .orElseGet(() -> corridors.save(
                        new Corridor(slug, origin, dest, label, true)));
    }

    /** @return {inserted, skipped} */
    @Transactional
    int[] importSamples(Corridor corridor, Path jsonl) throws IOException {
        // The natural key of a Phase 1 row. Loading it up front keeps the import to one
        // read plus one batch insert rather than a select per line.
        Set<String> existing = new HashSet<>();
        for (Sample sample : samples.findByCorridorId(corridor.getId())) {
            existing.add(key(sample.getDayOfWeek(), sample.getSlotHour(),
                    sample.getRequestedAt()));
        }

        List<Sample> batch = new ArrayList<>();
        int skipped = 0;
        for (String line : Files.readAllLines(jsonl)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode row = mapper.readTree(line);
            DayOfWeek day = DayOfWeek.valueOf(row.path("dayOfWeek").asText());
            int hour = row.path("slotHour").asInt();
            Instant requestedAt = Instant.parse(row.path("requestedAt").asText());

            if (!existing.add(key(day, hour, requestedAt))) {
                skipped++;
                continue;
            }
            JsonNode distance = row.path("distanceMeters");
            batch.add(new Sample(
                    corridor.getId(),
                    day,
                    hour,
                    row.path("durationSeconds").asInt(),
                    distance.isNull() || distance.isMissingNode() ? null : distance.asInt(),
                    requestedAt));
        }
        samples.saveAll(batch);
        return new int[] {batch.size(), skipped};
    }

    private static String key(DayOfWeek day, int hour, Instant requestedAt) {
        return day + "|" + hour + "|" + requestedAt;
    }
}
