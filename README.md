# Departure Time Forecaster

Google Maps tells you how long a trip takes **right now**. This tells you **when to leave**.

For five Bay Area commute corridors, it shows how driving time varies across day-of-week
and time-of-day, so you can see at a glance that leaving San Francisco for Oakland at
06:00 on a Friday costs 17 minutes and leaving at 17:00 on a Wednesday costs 36.

**Status: Phase 1 is code-complete and not yet deployed.** See
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
| Java 21 sampler (`sweep`, `aggregate`, `probe`, `spread`) | Working, 30 unit tests green |
| Sweeps run | Two, 2026-09-05 and 2026-09-12 — 910 calls, 910 rows, zero failures |
| `data/samples/*.jsonl` | 182 rows per route across 5 routes |
| `web/public/data/forecasts.json` | Built, 455 buckets, no empty cells, every `n = 2` |
| React heatmap, departure curve, leave-by panel | Working, 19 unit tests green |
| `npm run build` | Succeeds |
| Weekly GitHub Actions workflow | Written, **never run** |

Every one of the 455 grid cells is populated. The frontend renders the complete week
for all five corridors with no interaction required.

### What is left

Phase 1, in order:

1. **Push to GitHub.** The repository currently has no commits and no remote. Nothing
   downstream can happen until it does: the sampler workflow cannot run, and Cloudflare
   Pages has nothing to build.
2. **Set the `TOMTOM_API_KEY` repository secret**, then run the `sample` workflow once
   with `workflow_dispatch` to confirm it works in CI rather than only on a laptop.
3. **Deploy to Cloudflare Pages.** Root directory `web`, build command `npm run build`,
   output directory `dist`, with `NODE_VERSION` pinned in the Pages environment.
4. **Point the domain.** Registered at GoDaddy, nameservers moved to Cloudflare.

None of that waits on more data. The grid is already complete, and more weeks of
sampling would not make it more complete — see
[what re-sampling actually buys](#what-re-sampling-actually-buys) for why.

Phase 2 has not been started. No `service/` module exists yet. Its design is specified
in full in [`traffic-forecast-spec.md`](traffic-forecast-spec.md) §9, and the open
question it has to answer first is hosting: Cloud Run requires a GCP billing account
with a card on file, which is the same blocker that moved this project off Google's
routing API in the first place.

---

## Does the premise hold?

The app is pointless on corridors where the hour of departure does not matter. So that
was checked before anything was built on top of it, via the sampler's `spread` mode, and
it is worth reporting the answer rather than asserting it. From the committed data:

| Corridor | Distance | Best | Worst | Spread |
|---|---|---|---|---|
| SF Financial District → Downtown Oakland | 16.8 km | 17 min, Fri 06:00 | 36 min, Wed 17:00 | **107%** |
| San Jose → Palo Alto | 28.3 km | 22 min, Sun 06:00 | 43 min, Tue 08:00 | **95%** |
| Walnut Creek → SF Financial District | 38.2 km | 29 min, Sun 06:00 | 52 min, Wed 08:00 | **80%** |
| Fremont → Mountain View | 33.6 km | 24 min, Sun 06:00 | 42 min, Wed 08:00 | **74%** |
| SJSU → SF Financial District | 87.1 km | 59 min, Sat 06:00 | 97 min, Thu 17:00 | **63%** |

The worst corridor more than doubles in cost depending on when you leave. That is the
whole product in one table.

---

## What re-sampling actually buys

The sampler runs weekly, which invites a fair question: if a future `departAt` already
returns a precomputed average, what does asking again next week add?

That was worth measuring rather than assuming. Two full sweeps were run seven days
apart, on 2026-09-05 and 2026-09-12, covering the same 455 buckets. Comparing them cell
by cell:

| | |
|---|---|
| Cells returning an identical duration | 34 of 455 (7%) |
| Median change | 0.31% |
| Mean change | 0.42% |
| Largest change in any cell | 1.78% |
| Cells moving more than 5% | 0 |

The numbers do move, but barely. Hour-of-day swings on these corridors run from 63% to
107%; the largest week-over-week change anywhere in the grid was under 2%. Every
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
as the cache, Bucket4j for per-IP limits, a global daily counter with a kill switch, and
GraalVM native image compilation done last. Full design in the spec.

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
npm test            # 19 tests
```

Tests for both halves:

```bash
./gradlew :sampler:test      # 30 tests
cd web && npx vitest run     # 19 tests
```

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
