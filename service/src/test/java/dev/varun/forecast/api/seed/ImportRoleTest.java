package dev.varun.forecast.api.seed;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.varun.forecast.api.DatabaseTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;

/**
 * The role the weekly sweep imports with is a credential GitHub Actions holds, so what
 * it can reach is the whole of what a leak of it costs. These tests run the committed
 * grants script and then try the role's reach for real.
 *
 * <p>Creating a role needs CREATEROLE. CI's Postgres user is a superuser; a local one
 * usually is not, so locally these skip unless TEST_DB_USER names one that can.
 */
class ImportRoleTest extends DatabaseTest {

    private static final String ROLE = "forecast_import";
    private static final String PASSWORD = "import-role-test";
    /** Postgres's code for a permission failure, as opposed to a typo or a lost table. */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    @Value("${spring.datasource.url}")
    private String url;

    @BeforeEach
    void createTheRole() throws Exception {
        assumeTrue(Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT rolsuper OR rolcreaterole FROM pg_roles WHERE rolname = current_user",
                Boolean.class)), "the test database user cannot create roles");

        dropTheRole();
        String script = Files.readString(Path.of("scripts/neon-import-role.sql"))
                .replace("REPLACE_WITH_A_GENERATED_PASSWORD", PASSWORD);
        jdbc.execute(script);
    }

    /**
     * A role belongs to the whole server, not the test database, so one left behind would
     * be a login with a known password on whatever Postgres ran the tests.
     */
    @AfterEach
    void dropTheRole() {
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = ?)",
                Boolean.class, ROLE))) {
            jdbc.execute("DROP OWNED BY " + ROLE);
            jdbc.execute("DROP ROLE " + ROLE);
        }
    }

    private Connection asImportRole() throws SQLException {
        return DriverManager.getConnection(url, ROLE, PASSWORD);
    }

    @Test
    void canAddCorridorsAndSamples() throws Exception {
        try (Connection c = asImportRole(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO corridor (slug, origin_coord, dest_coord, label, seeded) "
                    + "VALUES ('role-test', '37.1,-122.1', '38.1,-121.1', 'x', true)");
            s.execute("INSERT INTO sample (corridor_id, day_of_week, slot_hour, "
                    + "duration_seconds, distance_meters, requested_at) "
                    + "SELECT id, 1, 6, 1800, 40000, now() FROM corridor "
                    + "WHERE slug = 'role-test'");
        }

        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM sample", Integer.class));
    }

    /**
     * The whole of the role's reach, read from the catalog, so a table added later is
     * covered without anyone remembering to list it here.
     */
    @Test
    void holdsNoGrantsBeyondReadingAndAddingHistory() {
        List<String> grants = jdbc.queryForList("""
                SELECT c.relname || ' ' || a.privilege_type
                FROM pg_class c, aclexplode(c.relacl) a
                WHERE a.grantee = ?::regrole
                ORDER BY 1
                """, String.class, ROLE);

        assertEquals(List.of(
                "corridor INSERT", "corridor SELECT",
                "corridor_id_seq USAGE",
                "sample INSERT", "sample SELECT",
                "sample_id_seq USAGE"), grants);
    }

    /** What an importer never needs, and so what a leaked credential must not reach. */
    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT * FROM ip_daily_usage",
        "SELECT * FROM daily_quota",
        "SELECT * FROM service_setting",
        "UPDATE sample SET slot_hour = 7",
        "DELETE FROM corridor",
        "CREATE TABLE junk (id int)"
    })
    void isRefused(String sql) throws Exception {
        try (Connection c = asImportRole(); Statement s = c.createStatement()) {
            SQLException thrown = assertThrows(SQLException.class, () -> s.execute(sql));
            assertEquals(INSUFFICIENT_PRIVILEGE, thrown.getSQLState(), thrown.getMessage());
        }
    }

    @Test
    void canLogIn() {
        assertDoesNotThrow(() -> asImportRole().close());
    }
}
