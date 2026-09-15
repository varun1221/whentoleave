-- Phase 2 schema. Flyway owns this file; JPA is configured to validate against it
-- rather than generate it, so a drift between the two fails at startup.

CREATE TABLE corridor (
  id            BIGSERIAL PRIMARY KEY,
  -- Stable, URL-safe identifier for the five Phase 1 corridors, so /api/routes/sjsu-sf
  -- keeps working and the static site and the service agree on route identity.
  -- Null for user-submitted corridors, which are addressed by coordinates.
  slug          TEXT UNIQUE,
  origin_coord  TEXT NOT NULL,   -- "lat,lon"
  dest_coord    TEXT NOT NULL,   -- "lat,lon"
  label         TEXT,
  seeded        BOOLEAN NOT NULL DEFAULT FALSE,
  first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (origin_coord, dest_coord)
);

CREATE TABLE sample (
  id               BIGSERIAL PRIMARY KEY,
  corridor_id      BIGINT NOT NULL REFERENCES corridor(id),
  day_of_week      SMALLINT NOT NULL,  -- ISO: Monday = 1 .. Sunday = 7
  slot_hour        SMALLINT NOT NULL,
  duration_seconds INTEGER NOT NULL,
  distance_meters  INTEGER,
  requested_at     TIMESTAMPTZ NOT NULL
);

-- The lookup path asks "recent samples for this corridor, this day, this hour",
-- newest first, which is exactly this index.
CREATE INDEX idx_sample_lookup
  ON sample (corridor_id, day_of_week, slot_hour, requested_at DESC);

CREATE TABLE daily_quota (
  day        DATE PRIMARY KEY,
  calls_made INTEGER NOT NULL DEFAULT 0
);
