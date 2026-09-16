package dev.varun.forecast.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The migrations are the schema of record — JPA is set to validate against them, not
 * generate them. If these fail, the service will not start in production either.
 */
class MigrationTest extends DatabaseTest {

    @Test
    void everyMigrationApplied() {
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class);

        assertEquals(List.of("1", "2", "3", "4"), versions);
    }

    @Test
    void theTablesTheServiceExpectsExist() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' ORDER BY table_name",
                String.class);

        assertTrue(tables.containsAll(
                List.of("corridor", "daily_quota", "ip_daily_usage", "sample",
                        "service_setting")),
                "got: " + tables);
    }

    /** The cache read depends on this index; without it the lookup path table-scans. */
    @Test
    void theSampleLookupIndexExists() {
        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'sample'",
                String.class);

        assertTrue(indexes.contains("idx_sample_lookup"), "got: " + indexes);
    }

    @Test
    void aCorridorCannotBeRegisteredTwiceForTheSameCoordinates() {
        jdbc.update("INSERT INTO corridor (origin_coord, dest_coord, seeded) "
                + "VALUES ('37.0,-122.0', '38.0,-121.0', false)");

        assertThrows(Exception.class,
                () -> jdbc.update("INSERT INTO corridor (origin_coord, dest_coord, seeded) "
                        + "VALUES ('37.0,-122.0', '38.0,-121.0', false)"));
    }

    @Test
    void slugIsUniqueButOptional() {
        jdbc.update("INSERT INTO corridor (slug, origin_coord, dest_coord, seeded) "
                + "VALUES ('a', '37.0,-122.0', '38.0,-121.0', true)");
        // Two user corridors with no slug must both be allowed.
        jdbc.update("INSERT INTO corridor (origin_coord, dest_coord, seeded) "
                + "VALUES ('37.1,-122.0', '38.0,-121.0', false)");
        jdbc.update("INSERT INTO corridor (origin_coord, dest_coord, seeded) "
                + "VALUES ('37.2,-122.0', '38.0,-121.0', false)");

        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM corridor",
                Integer.class));

        assertThrows(Exception.class,
                () -> jdbc.update(
                        "INSERT INTO corridor (slug, origin_coord, dest_coord, seeded) "
                                + "VALUES ('a', '37.3,-122.0', '38.0,-121.0', true)"));
    }

    @Test
    void theKillSwitchStartsEnabled() {
        assertEquals("true", jdbc.queryForObject(
                "SELECT value FROM service_setting WHERE key = 'lookups_enabled'",
                String.class));
    }
}
