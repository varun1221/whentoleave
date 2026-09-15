-- Per-IP daily budgets. In Postgres rather than in process because Cloud Run scales to
-- zero (an in-memory count is forgotten) and runs up to two instances (an in-memory
-- count is kept twice). §4: "Per-IP and global; survives scale-to-zero".
--
-- A fixed day rather than a rolling 24 hours per IP, so every visitor refills at the
-- same moment as the global counter and the UI has one reset time to show. The day is
-- Pacific; see QuotaDay.

CREATE TABLE ip_daily_usage (
  day    DATE    NOT NULL,
  budget TEXT    NOT NULL,  -- LOOKUP | SEARCH
  ip     TEXT    NOT NULL,
  used   INTEGER NOT NULL,
  PRIMARY KEY (day, budget, ip)
);
