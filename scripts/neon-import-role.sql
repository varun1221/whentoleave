-- The role the weekly `sample` workflow imports new sweeps with (issue #7).
--
-- GitHub Actions holds this credential, so what it can reach is the whole of what a
-- leak costs. It can read and add rows to `corridor` and `sample`, which hold public
-- traffic data. It can't touch the per-IP counters, the quota or the kill switch, can't
-- change or delete any row, and can't create tables.
--
-- Run it in the Neon SQL Editor, connected as the role that owns the tables (the one the
-- service connects as, which ran the Flyway migrations), after replacing the password.
-- The editor keeps its query history, password included, so clear this query from the
-- history afterwards.
-- Create the role here and not in the Neon console: console-made roles join
-- neon_superuser, which is exactly the reach this role exists not to have.
--
-- ImportRoleTest runs this file against the test database and checks each of those
-- limits, so change them there too.

CREATE ROLE forecast_import LOGIN PASSWORD 'REPLACE_WITH_A_GENERATED_PASSWORD';

GRANT USAGE ON SCHEMA public TO forecast_import;
GRANT SELECT, INSERT ON corridor, sample TO forecast_import;
GRANT USAGE ON SEQUENCE corridor_id_seq, sample_id_seq TO forecast_import;
