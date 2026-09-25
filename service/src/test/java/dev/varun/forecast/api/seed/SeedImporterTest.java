package dev.varun.forecast.api.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.varun.forecast.api.DatabaseTest;
import dev.varun.forecast.api.repo.CorridorRepository;
import dev.varun.forecast.api.repo.SampleRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;

class SeedImporterTest extends DatabaseTest {

    @Autowired
    private CorridorRepository corridors;

    @Autowired
    private SampleRepository samples;

    private SeedImporter importerFor(Path routes, Path samplesDir) {
        return new SeedImporter(corridors, samples, routes, samplesDir);
    }

    private static Path writeRoutes(Path dir, String json) throws IOException {
        Path file = dir.resolve("routes.json");
        Files.writeString(file, json);
        return file;
    }

    private static String sampleLine(String day, int hour, int seconds, String at) {
        return "{\"targetLocal\":\"2026-09-14T0" + hour + ":00\",\"dayOfWeek\":\"" + day
                + "\",\"slotHour\":" + hour + ",\"durationSeconds\":" + seconds
                + ",\"distanceMeters\":80000,\"requestedAt\":\"" + at + "\"}";
    }

    private static Stream<String> lines(Path file) {
        try {
            return Files.readAllLines(file).stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Without the flag this bean must do nothing: the service must not import at boot. */
    @Test
    void doesNothingWithoutTheFlag(@TempDir Path dir) throws Exception {
        Path routes = writeRoutes(dir, """
                [{"id":"a","name":"A","origin":"37.0,-122.0","dest":"38.0,-121.0"}]
                """);
        Files.writeString(dir.resolve("a.jsonl"),
                sampleLine("MONDAY", 6, 1000, "2026-09-13T13:00:00Z"));

        importerFor(routes, dir).run(new DefaultApplicationArguments());

        assertEquals(0, corridors.count());
        assertEquals(0, samples.count());
    }

    @Test
    void importsCorridorsAndSamples(@TempDir Path dir) throws Exception {
        Path routes = writeRoutes(dir, """
                [{"id":"a","name":"A to B","origin":"37.0,-122.0","dest":"38.0,-121.0"},
                 {"id":"b","name":"C to D","origin":"37.5,-122.5","dest":"38.5,-121.5"}]
                """);
        Files.writeString(dir.resolve("a.jsonl"), String.join("\n",
                sampleLine("MONDAY", 6, 1000, "2026-09-13T13:00:00Z"),
                sampleLine("MONDAY", 7, 2000, "2026-09-13T13:00:00Z")));
        Files.writeString(dir.resolve("b.jsonl"),
                sampleLine("TUESDAY", 8, 3000, "2026-09-13T13:00:00Z"));

        importerFor(routes, dir).run(new DefaultApplicationArguments("--import-seed"));

        assertEquals(2, corridors.count());
        assertEquals(3, samples.count());
        assertTrue(corridors.findBySlug("a").isPresent());
        assertTrue(corridors.findBySlug("a").get().isSeeded());
        assertEquals("A to B", corridors.findBySlug("a").get().getLabel());
    }

    /**
     * The importer runs after every weekly sweep, so a second run must add only what is
     * new rather than duplicating the history.
     */
    @Test
    void isIdempotent(@TempDir Path dir) throws Exception {
        Path routes = writeRoutes(dir, """
                [{"id":"a","name":"A","origin":"37.0,-122.0","dest":"38.0,-121.0"}]
                """);
        Files.writeString(dir.resolve("a.jsonl"), String.join("\n",
                sampleLine("MONDAY", 6, 1000, "2026-09-13T13:00:00Z"),
                sampleLine("MONDAY", 7, 2000, "2026-09-13T13:00:00Z")));

        importerFor(routes, dir).run(new DefaultApplicationArguments("--import-seed"));
        importerFor(routes, dir).run(new DefaultApplicationArguments("--import-seed"));
        importerFor(routes, dir).run(new DefaultApplicationArguments("--import-seed"));

        assertEquals(1, corridors.count());
        assertEquals(2, samples.count());
    }

    @Test
    void addsOnlyTheNewSweepOnASecondRun(@TempDir Path dir) throws Exception {
        Path routes = writeRoutes(dir, """
                [{"id":"a","name":"A","origin":"37.0,-122.0","dest":"38.0,-121.0"}]
                """);
        Path jsonl = dir.resolve("a.jsonl");
        Files.writeString(jsonl, sampleLine("MONDAY", 6, 1000, "2026-09-13T13:00:00Z"));
        importerFor(routes, dir).run(new DefaultApplicationArguments("--import-seed"));
        assertEquals(1, samples.count());

        // A later sweep appends a row for the same slot with a new requestedAt.
        Files.writeString(jsonl, String.join("\n",
                sampleLine("MONDAY", 6, 1000, "2026-09-13T13:00:00Z"),
                sampleLine("MONDAY", 6, 1100, "2026-09-20T13:00:00Z")));

        importerFor(routes, dir).run(new DefaultApplicationArguments("--import-seed"));

        assertEquals(2, samples.count(), "the new sweep is added, the old one is not");
    }

    /**
     * The importer and the lookup path must agree on coordinate identity, or a visitor
     * looking up a seeded corridor creates a duplicate of it and pays to refill a grid
     * that already exists.
     */
    @Test
    void normalisesCoordinatesTheSameWayLookupsDo(@TempDir Path dir) throws Exception {
        Path routes = writeRoutes(dir, """
                [{"id":"a","name":"A","origin":"37.33520, -121.88110","dest":"38.0,-121.0"}]
                """);
        Files.writeString(dir.resolve("a.jsonl"),
                sampleLine("MONDAY", 6, 1000, "2026-09-13T13:00:00Z"));

        importerFor(routes, dir).run(new DefaultApplicationArguments("--import-seed"));

        assertEquals("37.3352,-121.8811",
                corridors.findBySlug("a").orElseThrow().getOriginCoord());
        assertTrue(corridors.findByOriginCoordAndDestCoord("37.3352,-121.8811", "38,-121")
                .isPresent(), "a lookup for the same place must find this corridor");
    }

    @Test
    void aMissingSamplesFileSkipsThatRouteWithoutFailing(@TempDir Path dir)
            throws Exception {
        Path routes = writeRoutes(dir, """
                [{"id":"a","name":"A","origin":"37.0,-122.0","dest":"38.0,-121.0"},
                 {"id":"missing","name":"M","origin":"37.5,-122.5","dest":"38.5,-121.5"}]
                """);
        Files.writeString(dir.resolve("a.jsonl"),
                sampleLine("MONDAY", 6, 1000, "2026-09-13T13:00:00Z"));

        importerFor(routes, dir).run(new DefaultApplicationArguments("--import-seed"));

        assertEquals(2, corridors.count(), "the corridor is still registered");
        assertEquals(1, samples.count());
    }

    @Test
    void blankLinesAreIgnored(@TempDir Path dir) throws Exception {
        Path routes = writeRoutes(dir, """
                [{"id":"a","name":"A","origin":"37.0,-122.0","dest":"38.0,-121.0"}]
                """);
        Files.writeString(dir.resolve("a.jsonl"), "\n"
                + sampleLine("MONDAY", 6, 1000, "2026-09-13T13:00:00Z") + "\n\n");

        importerFor(routes, dir).run(new DefaultApplicationArguments("--import-seed"));

        assertEquals(1, samples.count());
    }

    @Test
    void aMissingRouteConfigIsReportedNotThrown(@TempDir Path dir) throws Exception {
        importerFor(dir.resolve("nope.json"), dir)
                .run(new DefaultApplicationArguments("--import-seed"));

        assertEquals(0, corridors.count());
    }

    /**
     * The committed Phase 1 dataset loads as-is, at the volume it actually has. That
     * volume is counted from the files rather than written down, because every weekly
     * sweep grows it and a hardcoded number broke on the next one.
     */
    @Test
    void importsTheRealCommittedDataset() throws Exception {
        Path samplesDir = Path.of("data/samples");
        long committed;
        try (Stream<Path> files = Files.list(samplesDir)) {
            committed = files.filter(f -> f.toString().endsWith(".jsonl"))
                    .flatMap(SeedImporterTest::lines)
                    .filter(line -> !line.isBlank())
                    .count();
        }

        importerFor(Path.of("config/routes.json"), samplesDir)
                .run(new DefaultApplicationArguments("--import-seed"));

        assertEquals(5, corridors.count());
        assertTrue(committed > 0, "the dataset is where this test expects it");
        assertEquals(committed, samples.count());
        assertTrue(corridors.findBySlug("sjsu-sf").isPresent());
    }
}
