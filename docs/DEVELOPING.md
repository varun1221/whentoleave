# Developing

You need JDK 21 and Node 20+. A free TomTom API key takes about two minutes to get at
[developer.tomtom.com](https://developer.tomtom.com) and **requires no credit card**.

```bash
cp .env.example .env       # paste your key in
set -a; source .env; set +a

./gradlew :sampler:shadowJar
java -jar sampler/build/libs/sampler.jar probe    # 1 call  — proves the API works
java -jar sampler/build/libs/sampler.jar spread   # 13 calls — proves the premise
java -jar sampler/build/libs/sampler.jar sweep    # 455 calls — full grid
```

`aggregate` rebuilds `forecasts.json` from the committed JSONL and makes **zero** API
calls, which is what you want while iterating on the frontend:

```bash
java -jar sampler/build/libs/sampler.jar aggregate
```

The site:

```bash
cd web
npm install
npm run dev         # http://localhost:5173
npm test            # 78 tests
```

`npm run dev` proxies `/api` to `http://localhost:8080`, so the lookup panel appears as
soon as the service (below) is running and hides itself when it is not. In production the
API is on its own hostname, set at build time as `VITE_API_BASE`.

Tests:

```bash
./gradlew build              # sampler + service, 243 tests
cd web && npm test           # 78 tests
```

The service tests need a Postgres to run against — a one-time setup:

```bash
brew install postgresql@16 && brew services start postgresql@16
createdb forecast && createdb forecast_test
psql postgres -c "CREATE ROLE forecast LOGIN PASSWORD 'forecast'"
psql -c "ALTER DATABASE forecast OWNER TO forecast"
psql -c "ALTER DATABASE forecast_test OWNER TO forecast"
```

**Why a real Postgres and not H2.** The two pieces of SQL this service most depends on
being right are the quota upsert's `ON CONFLICT` and the reserve step's
`SELECT ... FOR UPDATE`, and those are exactly where H2's Postgres compatibility mode
diverges. Testing on H2 would build confidence about behaviour the production database
does not have. Testcontainers is the usual answer, but it needs a Docker daemon, which
this project deliberately does not depend on — CI uses a Postgres service container
instead, which gets the same fidelity.

No test can spend API quota. `TOMTOM_API_KEY` is unset in the test configuration, and
every outbound call is either pointed at a local stub HTTP server or expected to fail.

Run the Phase 2 service locally:

```bash
./gradlew :service:bootRun --args='--import-seed'   # one-off: load the JSONL history
./gradlew :service:bootRun                        # then serve on :8080
```

With no `TOMTOM_API_KEY` set, the service serves cache only and never spends quota: a
cold corridor comes back as an empty grid and address search returns nothing, so paste a
`lat,lon` pair into the lookup panel instead of searching. The degraded states are
reachable without a key by poking the two control tables:

```bash
psql -d forecast -c "UPDATE service_setting SET value='false' WHERE key='lookups_enabled'"
psql -d forecast -c "UPDATE daily_quota SET calls_made=150 WHERE day=current_date"
```

A corridor with some cached buckets then answers 200 with a `notice` and, for the limits
that lift at midnight, a `resetsAt`; one with nothing cached answers 429 with the same
code. The kill switch is cached for 30 seconds, so a flip takes that long to show up.

## Repository layout

```
config/routes.json              the five corridors
sampler/                        the weekly sampler — plain Java 21, no Spring
  src/main/java/dev/varun/forecast/
    Main.java                   sweep | aggregate | probe | spread
    RoutingClient.java          throttle, retry, TomTom calls
    DepartureSchedule.java      zone-aware slot generation
    SampleStore.java            append-only JSONL
    Aggregator.java             medians → forecasts.json
data/samples/<routeId>.jsonl    append-only history, the real asset
service/                        the lookup API — Spring Boot 3
  src/main/java/dev/varun/forecast/api/
    web/                        controllers and error mapping
    service/                    lookup, search, limits, quota, kill switch
    repo/  domain/              Spring Data repositories and entities
    seed/SeedImporter.java      loads data/samples into Postgres
  src/main/resources/db/migration/   Flyway schema
web/                            Vite project root
  public/data/forecasts.json    served at /data/forecasts.json
  src/components/               Heatmap, DepartureCurve, LeaveByPanel, …
  src/lib/                      colorScale, leaveBy, format — pure, unit-tested
.github/workflows/              ci, weekly sample, deploy-service
scripts/                        GCP setup wizard, Neon import role
docs/                           design, development, deployment
traffic-forecast-spec.md        the original build spec, both phases
```
