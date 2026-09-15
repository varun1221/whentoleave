package dev.varun.forecast.api;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Base for tests that need the real database.
 *
 * <p>Cleanup is explicit rather than a rolled-back transaction, because the quota
 * reserve deliberately runs in its own transaction (REQUIRES_NEW) and so commits
 * outside any the test holds. A test that relied on rollback would pass while leaving
 * rows behind for the next one.
 */
@SpringBootTest
public abstract class DatabaseTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("DELETE FROM sample");
        jdbc.execute("DELETE FROM corridor");
        jdbc.execute("DELETE FROM daily_quota");
        jdbc.execute("DELETE FROM ip_daily_usage");
        jdbc.execute(
                "UPDATE service_setting SET value = 'true' WHERE key = 'lookups_enabled'");
    }
}
