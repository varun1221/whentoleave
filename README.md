# Departure Time Forecaster

Google Maps tells you how long a trip takes **right now**. This tells you **when to leave**.

For five Bay Area commute corridors, it shows how driving time varies across day-of-week
and time-of-day, so you can see at a glance that leaving San Francisco for Oakland at
06:00 on a Friday costs 17 minutes and leaving at 17:00 on a Wednesday costs 36.

**Status: Phase 1 is live at [whentoleave.me](https://whentoleave.me).** See
[Where this is](#where-this-is) for exactly what works today and what is left.

---

## The honest framing

TomTom's routing API, when given a `departAt` in the future, does **not** return a live
forecast. It prices the trip off its historical speed-profile database. That is the
behaviour this entire project rests on, and it is worth being precise about:

> This app surfaces and reshapes a model TomTom already has into a view neither TomTom
> nor Google Maps offers. It does not out-predict either of them.

The value here is the **view** — a full week × hour grid you can read in one glance —
and the engineering around keeping that view free to run and honest about its own
limits. Claiming predictive power would be the one way this project could embarrass
itself.

---

## Where this is

The project ships in two phases. Phase 1 is the demo: a static site backed by a weekly
sampler, with no server anywhere. Phase 2 is the depth: a Spring Boot service that lets
a visitor look up their own route. **Phase 1 must be live before Phase 2 begins.**

### What works right now

| Piece | State |
|---|---|
| Java 21 sampler (`sweep`, `aggregate`, `probe`, `spread`) | Working, 32 unit tests green |
| Sweeps run | Four: 2026-09-05, 09-12, 09-13, 09-20 — 1,820 calls, 1,820 rows, zero failures |
| `data/samples/*.jsonl` | 364 rows per route across 5 routes |
| `web/public/data/forecasts.json` | Built, 455 buckets, no empty cells, every `n = 4` |
| Weekly `sample` workflow | Two manual runs on 2026-09-12, then unattended on 09-13 and 09-20, committing its own data each time |
| Phase 2 service | Steps 8–12 done locally: schema, seed import, lookup, limits, search |
| Tests | 292 passing — 32 sampler, 182 service, 78 web |
| React heatmap, departure curve, leave-by panel | Working, 71 unit tests green |
| Lookup panel (step 15) | Built; 10 tests drive the form itself — picking a place, the 429, an unreachable service |
| `npm run build` | Succeeds |
| Live site | **Deployed 2026-09-20** — [whentoleave.me](https://whentoleave.me) and `www`, on Cloudflare Pages, HTTPS with a valid cert, `_headers` confirmed applying at the edge |
| Native image (step 14) | Configured — GraalVM plugin, reflection hints, `Dockerfile.native` — and **never built**: see below |
| `deploy-service.yml` + both Dockerfiles | Written, **never run**; no GCP account yet |

Every one of the 455 grid cells is populated. The frontend renders the complete week
for all five corridors with no interaction required.

### What is left

Phase 1, in order:

1. ~~**Push to GitHub.**~~ Done — `main` is on `varun1221/forecastapp`.
2. ~~**Set the `TOMTOM_API_KEY` repository secret** and run the `sample` workflow in
   CI.~~ Done — secret set 2026-09-12, and the workflow has run three times since.
3. ~~**Deploy to Cloudflare Pages.**~~ Done 2026-09-20 — root directory `web`, build
   command `npm run build`, output directory `dist`, `NODE_VERSION` pinned to 22 so the
   Pages build matches the one CI tests.
4. ~~**Point the domain.**~~ Done 2026-09-20 — `whentoleave.me` registered at Namecheap,
   nameservers moved to Cloudflare, and both the apex and `www` attached as Pages custom
   domains. The zone lives in Cloudflare, which is what step 13's Transform Rule needs;
   a domain merely pointed at Pages would not have one.

Phase 1 is complete as of 2026-09-20. The grid it renders was already full before the
deploy, and more weeks of sampling would not make it more complete — see
[what re-sampling actually buys](#what-re-sampling-actually-buys) for why.

Phase 2, in order:

1. **Deploy the service to Cloud Run** (spec §9.7) as a plain JVM jar. The pipeline is
   written — `service/Dockerfile` and `deploy-service.yml`, described under
   [Deploying the service](#deploying-the-service) — and waits on one thing: Cloud Run
   requires a GCP billing account with a card on file, the same blocker that moved this
   project off Google's routing API in the first place. The jar image builds on a laptop;
   what has never happened is a push to Artifact Registry, and the workflow has never run.
2. **Native image, last.** Configured but not yet built: the GraalVM plugin, the
   reflection hints every Jackson-bound record needs, and `service/Dockerfile.native`
   are all in place, and Spring's AOT processing runs clean. The compile itself is what
   is missing — `native-image`'s static analysis was OOM-killed (exit 137) at 4m53s on a
   laptop with 8GB of RAM, of which the Docker VM can have less than half. It wants a
   machine with more memory than this one has, so the honest place to build it is CI.
   Cold start measured before and after, both numbers here, once it has been built
   somewhere that can build it.
3. **Point the frontend at the deployed API** by setting `VITE_API_BASE` in the Pages
   environment. Until then the lookup panel hides itself, which is the correct behaviour
   for a site whose backend is not up yet.

The service and the lookup panel are written and green locally. The static site now
serves real traffic on its own domain; what has not happened is an **API** request served
over the internet.

---

## Does the premise hold?

The app is pointless on corridors where the hour of departure does not matter. So that
was checked before anything was built on top of it, via the sampler's `spread` mode, and
it is worth reporting the answer rather than asserting it. From the committed data:

| Corridor | Distance | Best | Worst | Spread |
|---|---|---|---|---|
| SF Financial District → Downtown Oakland | 16.8 km | 17 min, Sat 06:00 | 36 min, Wed 17:00 | **108%** |
| San Jose → Palo Alto | 28.3 km | 22 min, Sun 06:00 | 43 min, Tue 08:00 | **94%** |
| Walnut Creek → SF Financial District | 38.2 km | 29 min, Sun 06:00 | 52 min, Wed 08:00 | **80%** |
| Fremont → Mountain View | 33.6 km | 24 min, Sun 06:00 | 42 min, Wed 08:00 | **74%** |
| SJSU → SF Financial District | 87.1 km | 59 min, Sun 06:00 | 97 min, Thu 17:00 | **66%** |

The worst corridor more than doubles in cost depending on when you leave. That is the
whole product in one table.

---

## What re-sampling actually buys

The sampler runs weekly, which invites a fair question: if a future `departAt` already
returns a precomputed average, what does asking again next week add?

That was worth measuring rather than assuming. Three full sweeps now exist, and
comparing the first against the most recent — 2026-09-05 against 2026-09-13, eight days
apart — across the same 455 buckets gives:

| | |
|---|---|
| Cells returning an identical duration | 34 of 455 (7%) |
| Median change | 0.31% |
| Mean change | 0.43% |
| Largest change in any cell | 4.42% |
| Cells moving more than 5% | 0 |

The numbers do move, but barely. Hour-of-day swings on these corridors run from 63% to
107%; the largest change anywhere in the grid was under 5%, and the median cell moved a
third of one percent. Every
corridor's spread landed within a point of where a single sweep had already put it, and
the best and worst cells did not move.

Most of that small jitter appears to be the router rather than the traffic model. On the
longest corridor, cells where TomTom returned a different `lengthInMeters` — a
physically different route — moved about three times as much as cells where the distance
came back identical.

**So weekly sampling is worth running, but not for the reason it looks like.** It is not
that the medians get better:

- **Drift over months, not weeks.** Speed profiles get rebuilt as new probe data
  arrives, and roads change. One week shows almost nothing; a year should show
  something, and the append-only history is what makes it visible.
- **Resilience.** A sweep that hits a rate limit or a transient 5xx leaves the previous
  week's values in place rather than punching holes in the grid.
- **A live pipeline beats a dead one.** A cron job that has run unattended for months is
  evidence the thing works.

What it does not buy is statistical confidence. Six reads of one precomputed average are
still one precomputed average. That is why the grid was complete and deployable after
the first sweep, and why waiting for more weeks was never a gate.

---

## Architecture

### Phase 1 — no server at all

```
GitHub Actions (weekly cron, Sunday 02:00 PT)
        │
        ├─ Java sampler
        │     ├─ reads config/routes.json (5 corridors)
        │     ├─ for each route × next 7 days × 13 hours (06:00–18:00):
        │     │     calculateRoute with a future departAt
        │     ├─ appends to data/samples/<routeId>.jsonl
        │     └─ rebuilds web/public/data/forecasts.json
        └─ commits back to the repo
                    ▼
        Cloudflare Pages auto-deploys web/  →  React site
```

5 routes × 7 days × 13 hours = **455 calls fills the entire grid in one sweep**. That
matters more than it looks: it means the demo was data-complete the same afternoon the
sampler first ran, rather than filling in slowly over a month. Weekly reruns then track
drift in the underlying speed profiles, which is a smaller job than filling the grid and
is discussed [below](#what-re-sampling-actually-buys).

There is deliberately no code path from a browser to the API key in Phase 1. Free
server tiers that sleep produce 30-second cold starts and make a demo look broken.

### Phase 2 — planned, not built

A Spring Boot 3 service on Cloud Run behind Cloudflare, with the `sample` table doubling
as the cache, per-IP and global daily counters in Postgres with a kill switch, and
GraalVM native image compilation done last. Full design in the spec.

The spec names Bucket4j for the per-IP limit; the service uses a Postgres day counter
instead. An in-process bucket is forgotten when Cloud Run scales to zero and counted
twice across two instances, and a rolling per-IP window gives the UI no single reset
time to show. Both limits reset at Pacific midnight.

**Why no framework in Phase 1 and a framework in Phase 2** is the single most useful
thing about this project to be able to explain. A 90-second cron job that reads a
config, makes HTTP calls, and writes files uses none of what Spring provides — no
dependency injection worth the name, no servlet container, no controllers, no JPA.
Adding it there would signal not understanding what it is for. A rate-limited, cached,
publicly exposed API is exactly what Spring is for. The split is the point.

---

## Running it locally

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
npm test            # 71 tests
```

`npm run dev` proxies `/api` to `http://localhost:8080`, so the lookup panel appears as
soon as the service (below) is running and hides itself when it is not. In production the
API is on its own hostname, set at build time as `VITE_API_BASE`.

Tests:

```bash
./gradlew build              # sampler + service, 214 tests
cd web && npm test           # 71 tests
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

---

## Deploying the service

`deploy-service.yml` runs on every push to `main` that touches `service/**`: build the
image, push it to Artifact Registry, `gcloud run deploy` with the §7 flags. It holds **no
long-lived credential** — GitHub authenticates as this repository via Workload Identity
Federation, and everything secret lives in GCP Secret Manager, so nothing sensitive
passes through Actions at all.

Which of the two images it builds is a switch, not a rewrite: `FLAVOR` defaults to `jar`,
a `workflow_dispatch` can pick `native` for one run, and the repository variable
`SERVICE_FLAVOR` makes that the standing choice. The default is the jar because that is
the path verified over HTTP — §9.6 puts the native image strictly after a working deploy,
and a switch keeps that ordering from being an accident of which file the workflow names.

That means some one-time setup outside the repo. In GCP, once:

```bash
PROJECT=your-project-id
REGION=us-central1              # or us-east1 / us-west1 — the free tier exists nowhere else

gcloud artifacts repositories create forecast \
  --repository-format=docker --location="$REGION"

# The secrets the service reads. Generate the origin secret and the IP hash key here;
# they exist nowhere else and nothing needs to know their values.
printf '%s' "$(openssl rand -hex 32)" | gcloud secrets create forecast-origin-secret  --data-file=-
printf '%s' "$(openssl rand -hex 32)" | gcloud secrets create forecast-ip-hash-secret --data-file=-
printf '%s' "$TOMTOM_API_KEY"         | gcloud secrets create forecast-tomtom-key     --data-file=-
printf '%s' 'jdbc:postgresql://…neon.tech/forecast?sslmode=require' \
                                      | gcloud secrets create forecast-db-url         --data-file=-
printf '%s' "$NEON_PASSWORD"          | gcloud secrets create forecast-db-password    --data-file=-
```

Then two service accounts, which is the detail worth getting right first time:

- the **deploy** account GitHub impersonates needs `run.admin`,
  `artifactregistry.writer` and `iam.serviceAccountUser`;
- the **runtime** account the revision runs as (the default compute account, unless you
  pass `--service-account`) needs `secretmanager.secretAccessor` — it is what actually
  reads the five secrets, and `gcloud run deploy` checks that at deploy time, so granting
  the deploy account instead fails with a permissions error that names the wrong
  identity.

Plus a Workload Identity pool whose provider is restricted to this repository — the
restriction is the point, since without it any repository could mint the same token.
Finally, five **repository variables**
(Settings → Secrets and variables → Actions → Variables) — none are credentials:

| Variable | Example |
|---|---|
| `GCP_PROJECT_ID` | `forecast-470112` |
| `GCP_REGION` | `us-central1` |
| `GCP_WIF_PROVIDER` | `projects/123456789/locations/global/workloadIdentityPools/github/providers/forecastapp` |
| `GCP_DEPLOY_SA` | `deploy@forecast-470112.iam.gserviceaccount.com` |
| `DB_USER` | `forecast` |

**The seeded history is a separate, one-off import**, not part of the image: point the
importer at Neon from a laptop, once, rather than shipping `data/` to production.

```bash
DB_URL=... DB_USER=... DB_PASSWORD=... ./gradlew :service:bootRun --args='--import-seed'
```

Two things the deploy cannot do for itself, both in issue #6: a **Cloudflare Transform
Rule** on the API hostname setting `X-Origin-Secret` to the value of
`forecast-origin-secret`, and `VITE_API_BASE` set to that hostname in the Pages
environment. The workflow's last step fails loudly until the first of those exists — it
asks Cloud Run directly for `/api/quota` and expects a 403, because a 200 there means the
origin is trusting `CF-Connecting-IP` from anyone and the per-IP limits can be bypassed by
sending a different one each time.

---

## Cost

The only recurring cost is the domain, roughly $22/year at renewal. Everything else is
free tier, and **Phase 1 needs no payment method anywhere** — TomTom freemium, GitHub
Actions on a public repo, Cloudflare Pages and Cloudflare DNS are all card-free.

The arithmetic, rather than a hope:

```
TomTom freemium:   20,000 routing requests/month, 2,500/day
Sampler:           5 routes × 7 days × 13 hours = 455 per sweep
                   455 × 4 sweeps = 1,820/month
Headroom:          ~18,000/month, about 10× what the sampler consumes
```

### Guardrails

Because the freemium tier has no payment method attached, an overrun cannot produce a
bill — only rejected requests. The vendor ceiling is a wall, not a meter. The guardrails
exist to make an overrun **loud and early** rather than a silently half-empty sweep.

- **`MAX_CALLS` ceiling**, default 500, checked against the planned call count *before
  the first request* and aborting with a clear log line.
- **~2 requests/second throttle** (500 ms minimum interval), comfortably under the
  freemium rate limit. A 455-call sweep takes about four minutes.
- **Retry 429 and 5xx only**, three attempts, exponential backoff. A 400 or 403 is a
  malformed coordinate or a bad key — retrying it burns the monthly allowance against a
  request that cannot succeed.
- **Partial failure is not fatal.** One bad coordinate pair does not cost the sweep; the
  process exits non-zero only if every single call failed.
- The key lives in GitHub Secrets and a gitignored `.env`. Never in the repo, never
  in `web/`.

### Why departure times cannot be batched

A matrix endpoint batches origins × destinations but carries exactly one departure time
per request. Every sampled hour is therefore its own billable call, and no amount of
cleverness collapses 455 into fewer. This is the constraint that sets the entire
sampling budget, and in Phase 2 it is why a cold user-submitted corridor gets a reduced
grid rather than the full 91 slots.

---

## Design notes

**The samples are the asset.** `data/samples/*.jsonl` is append-only and never
rewritten. `forecasts.json` is a derived artifact that can be rebuilt from it at any
time with `aggregate`. Roughly 64 KB per week, 3.3 MB per year — small enough that the
git repository is a perfectly good store, and no code path truncates those files.

**DST correctness.** Slot arithmetic uses `ZonedDateTime` against `America/Los_Angeles`,
never a fixed UTC offset. Two weekends a year would otherwise silently shift every
sample by an hour, and the resulting heatmap would be wrong in a way nobody would notice
for months.

**Median, not mean.** One accident on one Tuesday should not redefine what Tuesdays look
like.

**Empty buckets emit `null`, never `0`.** A zero renders as an instant trip and a bright
green cell, which is the most confidently wrong thing a heatmap can do. The UI styles
nulls explicitly as absent.

**Derived values are derived.** The colour range, the best and worst cells, and the
leave-by result are all `useMemo` over props, not state mirrored from them.

**The heatmap is CSS Grid, not a chart library.** A heatmap is a grid of coloured boxes,
and Recharts is loaded only for the departure curve.

Dependency discipline throughout: no Tailwind, no component library, no state manager,
no date library, no Redis, no Lombok. Jackson is the sampler's only dependency. Every
dependency is something that can break a build six months from now.

---

## Known limitations

These are stated rather than hidden, and the low-`n` ones are surfaced in the UI itself.

- **`n` counts how many times we asked, not how many commutes were observed.** Because
  a future `departAt` returns a precomputed average, a bucket with `n = 6` is six reads
  of TomTom's model, not six Tuesdays. The UI shows the count because it is honest
  about sampling effort, but it should not be read as a confidence interval.
- **The underlying data is a historical average**, not a prediction. It cannot know
  about today's accident, today's game, or today's weather.
- **Five hardcoded corridors, 06:00–18:00 only.** Arbitrary routes are Phase 2; nights
  are out of scope because the interesting variance is not there.
- **GitHub disables scheduled workflows after 60 days of repository inactivity**, and
  a `GITHUB_TOKEN` push may not reset that timer. If the data goes stale, check that the
  workflow is still enabled before debugging anything else.
- **Seeded coordinates are city-centre approximations.** A corridor whose spread looks
  wrong should be refined to a specific address.

---

## Repository layout

```
config/routes.json              the five corridors
sampler/                        Phase 1 — plain Java 21, no Spring
  src/main/java/dev/varun/forecast/
    Main.java                   sweep | aggregate | probe | spread
    RoutingClient.java          throttle, retry, TomTom calls
    DepartureSchedule.java      zone-aware slot generation
    SampleStore.java            append-only JSONL
    Aggregator.java             medians → forecasts.json
data/samples/<routeId>.jsonl    append-only history, the real asset
web/                            Vite project root
  public/data/forecasts.json    served at /data/forecasts.json
  src/components/               Heatmap, DepartureCurve, LeaveByPanel, …
  src/lib/                      colorScale, leaveBy, format — pure, unit-tested
.github/workflows/sample.yml    weekly cron + manual dispatch
traffic-forecast-spec.md        the full build spec, both phases
```
