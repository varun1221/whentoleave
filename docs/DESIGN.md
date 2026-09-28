# Design

How the app works, why it is built the way it is, and what it honestly can and cannot
tell you. For running it, see [DEVELOPING.md](DEVELOPING.md); for deploying it,
[DEPLOYING.md](DEPLOYING.md).

- [The honest framing](#the-honest-framing)
- [Does the premise hold?](#does-the-premise-hold)
- [What re-sampling actually buys](#what-re-sampling-actually-buys)
- [Architecture](#architecture)
- [Cost and guardrails](#cost-and-guardrails)
- [Design notes](#design-notes)
- [Known limitations](#known-limitations)
- [Project history](#project-history)

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

## What re-sampling actually buys

The sampler runs weekly, which invites a fair question: if a future `departAt` already
returns a precomputed average, what does asking again next week add?

That was worth measuring rather than assuming. When three full sweeps existed, comparing
the first against the most recent — 2026-09-05 against 2026-09-13, eight days apart —
across the same 455 buckets gave:

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

### Phase 2 — a lookup service behind Cloudflare

```
browser (whentoleave.me, lookup panel)
        │  fetch, CORS-allowed only from whentoleave.me and www
        ▼
Cloudflare  api.whentoleave.me   (proxied CNAME → ghs.googlehosted.com)
        ├─ rate-limit rule api-per-ip: blocks one IP that asks too often
        └─ Transform Rule origin-secret: adds X-Origin-Secret
        ▼
Cloud Run  forecast-service   (Spring Boot 3 jar, us-west1, 0–2 instances)
        ├─ refuses API calls without the origin secret, so the per-IP limits
        │  cannot be sidestepped by calling the *.run.app URL directly
        ├─ cache-first: a fresh `sample` row is served without calling TomTom
        └─ per-IP and global daily counters, a kill switch
        ▼
Neon Postgres  (corridor, sample, counters)   +   TomTom routing and search
```

Live since 2026-09-26. The `sample` table doubles as the cache, per-IP and global daily
counters live in Postgres beside it, and a kill switch pauses every paid call at once.
The spec's last step, a GraalVM native image, was dropped: the service runs as a plain
JVM jar. Full design in the [spec](../traffic-forecast-spec.md).

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

## Cost and guardrails

The only recurring cost is the domain, roughly $22/year at renewal. Everything else is
free tier, and **Phase 1 needs no payment method anywhere** — TomTom freemium, GitHub
Actions on a public repo, Cloudflare Pages and Cloudflare DNS are all card-free.

The arithmetic, rather than a hope:

```
TomTom freemium:   Routing 2,500/day (20,000/month); Search 2,500/day, a separate
                   allowance. One API key, shared by the sampler and the service.
Sampler:           5 routes × 7 days × 13 hours = 455 per sweep
                   455 × 4 sweeps = 1,820/month
Service routing:   capped at 150/day (forecast.quota.daily-ceiling)
                   worst day, a Sunday: 455 + 150 = 605 of 2,500
                   worst month: 1,820 + 150 × 30 = 6,320 of 20,000
Service search:    capped at 1,000/day (forecast.search.daily-ceiling) of 2,500
```

Because the service's caps are hard stops, it can't use enough of the shared key to
starve a sweep, even on the day they overlap. Allowances checked in the TomTom dashboard
on 2026-09-28.

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

The service adds its own, because it is the one piece a stranger can make spend money:

- **Global daily ceiling** on routing calls (`forecast.quota.daily-ceiling`, 150), checked
  and spent under a row lock so concurrent lookups cannot overshoot it.
- **Shared daily search ceiling** of 1000 (`forecast.search.daily-ceiling`), so many
  visitors' address searches cannot add up to unlimited search calls.
- **Per-IP daily limits** on lookups and searches, IPv6 keyed by /64. IPs are HMAC'd
  before they are stored, and address-search text is never logged.
- **New corridors cost a visitor something.** A corridor row is only written after the kill
  switch, the per-IP limit and the global ceiling have all passed, and only once a
  sample has been fetched for it.
- **Only calls that reach TomTom count.** A lookup that sends nothing is refunded.
- **CORS** allows only `https://whentoleave.me` and `https://www.whentoleave.me`
  (`forecast.edge.allowed-origins`).
- **Cloudflare rate-limit rule** `api-per-ip`, which blocks a flood before it reaches Cloud
  Run.
- **Cloud Run at 0–2 instances**, plus a **$1 GCP spend cap** scoped to Cloud Run.
- **Artifact Registry cleanup policy**: keep the last five images, delete anything older
  than a day, so image storage stays inside the free tier.

### Why departure times cannot be batched

A matrix endpoint batches origins × destinations but carries exactly one departure time
per request. Every sampled hour is therefore its own billable call, and no amount of
cleverness collapses 455 into fewer. This is the constraint that sets the entire
sampling budget, and in Phase 2 it is why a cold user-submitted corridor gets a reduced
grid rather than the full 91 slots.

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

## Known limitations

These are stated rather than hidden, and the low-`n` ones are surfaced in the UI itself.

- **`n` counts how many times we asked, not how many commutes were observed.** Because
  a future `departAt` returns a precomputed average, a bucket with `n = 6` is six reads
  of TomTom's model, not six Tuesdays. The UI shows the count because it is honest
  about sampling effort, but it should not be read as a confidence interval.
- **The underlying data is a historical average**, not a prediction. It cannot know
  about today's accident, today's game, or today's weather.
- **Five seeded corridors get the full week, 06:00–18:00.** Any other route gets a
  reduced weekday grid (5 days × 9 peak hours), because every departure hour is its own
  billable call. Nights are out of scope because the interesting variance is not there.
- **GitHub disables scheduled workflows after 60 days of repository inactivity**, and
  a `GITHUB_TOKEN` push may not reset that timer. If the data goes stale, check that the
  workflow is still enabled before debugging anything else.
- **Seeded coordinates are city-centre approximations.** A corridor whose spread looks
  wrong should be refined to a specific address.

## Project history

The project shipped in two phases, and Phase 1 had to be live before Phase 2 began.

| Date | Milestone |
|---|---|
| 2026-09-05 | First full sweep: 455 calls, the whole grid in one run |
| 2026-09-13 | The weekly `sample` workflow starts running unattended every Sunday |
| 2026-09-20 | **Phase 1 live** at whentoleave.me on Cloudflare Pages |
| 2026-09-26 | **Phase 2 live**: the lookup API on Cloud Run at api.whentoleave.me, and the lookup panel on the site |
| 2026-09-28 | Weekly sweeps load into Neon automatically, so the site and the API always agree |

The spec's last step, compiling the service to a GraalVM native image, was dropped. It
was configured (plugin, reflection hints and a native Dockerfile, with Spring's AOT
processing running clean) but never built: `native-image` was OOM-killed at 4m53s on an
8GB laptop, and the jar serves the site well enough that a bigger build machine wasn't
worth it.
