package dev.varun.forecast;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.varun.forecast.model.Sample;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only JSONL store, one file per route, under data/samples/.
 *
 * <p>There is deliberately no write, replace, or delete operation here. The accumulated
 * history is the project's asset; a code path that could truncate it does not exist.
 */
public final class SampleStore {

    private final Path dir;
    private final ObjectMapper mapper = new ObjectMapper();

    public SampleStore(Path dir) {
        this.dir = dir;
    }

    public Path fileFor(String routeId) {
        return dir.resolve(routeId + ".jsonl");
    }

    public void append(String routeId, Sample sample) throws IOException {
        Files.createDirectories(dir);
        String line = mapper.writeValueAsString(sample) + System.lineSeparator();
        Files.writeString(
                fileFor(routeId),
                line,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    /** Every sample recorded for a route, oldest first. Missing file means no history. */
    public List<Sample> readAll(String routeId) throws IOException {
        Path file = fileFor(routeId);
        if (!Files.exists(file)) {
            return List.of();
        }
        List<Sample> samples = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            samples.add(mapper.readValue(line, Sample.class));
        }
        return samples;
    }
}
