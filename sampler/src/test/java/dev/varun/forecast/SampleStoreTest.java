package dev.varun.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.varun.forecast.model.Sample;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SampleStoreTest {

    @Test
    void appendsWithoutDisturbingWhatIsAlreadyThere(@TempDir Path dir) throws IOException {
        SampleStore store = new SampleStore(dir);
        store.append("sjsu-sf", sample(1800));
        store.append("sjsu-sf", sample(2000));
        store.append("sjsu-sf", sample(2200));

        List<Sample> read = store.readAll("sjsu-sf");
        assertEquals(3, read.size());
        assertEquals(1800, read.get(0).durationSeconds());
        assertEquals(2200, read.get(2).durationSeconds());
    }

    @Test
    void roundTripsEveryField(@TempDir Path dir) throws IOException {
        SampleStore store = new SampleStore(dir);
        Sample original = sample(2461);
        store.append("sjsu-sf", original);
        assertEquals(original, store.readAll("sjsu-sf").get(0));
    }

    @Test
    void aRouteWithNoHistoryReadsAsEmpty(@TempDir Path dir) throws IOException {
        assertEquals(List.of(), new SampleStore(dir).readAll("never-sampled"));
    }

    private static Sample sample(long seconds) {
        return new Sample("2026-09-02T08:00", "WEDNESDAY", 8, seconds, 78234L,
                "2026-08-30T09:02:11Z");
    }
}
