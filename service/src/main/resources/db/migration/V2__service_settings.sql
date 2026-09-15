-- The kill switch. A row rather than a config property because it has to be flippable
-- on a running service without a redeploy, and has to survive scale-to-zero.
--
-- Deliberately no HTTP endpoint to toggle it: an unauthenticated admin route on a
-- public service would be a worse problem than the one it solves. Flip it with SQL:
--   UPDATE service_setting SET value = 'false' WHERE key = 'lookups_enabled';

CREATE TABLE service_setting (
  key        TEXT PRIMARY KEY,
  value      TEXT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO service_setting (key, value) VALUES ('lookups_enabled', 'true');
