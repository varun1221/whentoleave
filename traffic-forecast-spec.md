# Project Spec: Departure Time Forecaster

The build spec for this project. Read the whole document before writing any code.

The project ships in two phases. **Phase 1 must be complete, deployed, and live on the custom domain before Phase 2 begins.** Phase 1 is the demo; Phase 2 is the depth. Do not interleave them.

---

## 1. What we're building

Google Maps tells you how long a trip takes right now. This tells you **when to leave**.

For a set of commute corridors, the app shows how driving time varies across day-of-week and time-of-day, so you can see that leaving at 07:00 costs 28 minutes and leaving at 08:00 costs 41.

**Phase 1** — a static site backed by a weekly sampler. Five seeded corridors, complete heatmap from day one, no server.

**Phase 2** — a Spring Boot service that lets a visitor look up their own route, cached and rate-limited so it stays free.

Views, in priority order:

1. **Weekly heatmap** (hero) — day-of-week × hour grid, colored by travel duration. Google Maps has no equivalent view.
2. **Departure curve** — line chart of duration vs. hour for one route on one day.
3. **Leave-by panel** — pick a route and arrival deadline, get the latest safe departure plus the penalty for leaving later.
4. **Try your own route** (Phase 2) — arbitrary origin/destination lookup.

This is a portfolio project for backend/full-stack internship applications. It must be publicly reachable at a custom domain so an interviewer can click a link and immediately see a full, working product.

### The one thing to be accurate about

TomTom's future-`departAt` responses are **historical averages** drawn from its speed-profile database, not live forecasts. This app surfaces and reshapes that model into a view neither TomTom nor Google Maps offers — it does not out-predict either. Say exactly this in the README and in interviews. Claiming more is the only way this project can embarrass you.

---

## 2. Hard constraints

Non-negotiable across both phases.

- **No Python.** Anywhere.
- **No TypeScript.** Plain JavaScript on the frontend.
- **No recurring cost except the domain.** No paid hosting, database, or API usage.
- **Static frontend.** A build step is fine (Cloudflare Pages runs it), but the frontend must never require a Node process at request time — no SSR, no API routes, no Next.js server mode.
- **Phase 1 has no server at all.** Free tiers that sleep produce 30+ second cold starts and make the demo look broken.
- **The site must work with the Phase 2 backend down.** The static heatmap always renders from committed JSON. The lookup panel degrades gracefully.

---

## 3. Architecture

### Phase 1

```
GitHub Actions (weekly cron, Sunday 02:00 PT)
        │
        ├─ Java sampler
        │     ├─ reads config/routes.json (5 corridors)
        │     ├─ for each route × next 7 days × 13 hours (06:00–18:00):
        │     │     calculateRoute call with future departAt
        │     ├─ appends to data/samples/<routeId>.jsonl
        │     └─ rebuilds web/public/data/forecasts.json
        └─ commits back to the repo
                    ▼
        Cloudflare Pages auto-deploys web/  →  React site at custom domain
```

455 calls per run (5 × 7 × 13) fills the **entire** grid in one sweep. Run it manually once and the demo is data-complete that afternoon. Weekly reruns take a median across weeks, so data improves without the demo ever looking sparse.

### Phase 2

```
Browser
   │
   ├─ static heatmap  ──────────────►  Cloudflare Pages (forecasts.json)
   │
   └─ POST /api/lookup ─────────────►  Cloudflare (edge rate limit)
                                          ▼
                                     Cloud Run (us-central1, scale-to-zero)
                                     Spring Boot 3, GraalVM native image
                                          ▼
                                     Bucket4j per-IP filter
                                          ▼
                                     global daily counter (Postgres)
                                          ▼
                                     Caffeine hot cache
                                          ▼
                                     Postgres (Neon) — bucket < 7d old?
                                        hit  → return, zero API calls
                                        miss → Resilience4j-wrapped
                                               calculateRoute call → persist → return
```

In Phase 1 there is no code path from a browser to the TomTom API key. In Phase 2 there is, by design — which is why §7 guardrails become load-bearing rather than precautionary.

---

## 4. Tech stack

### Phase 1

| Layer | Choice | Notes |
|---|---|---|
| Sampler | **Java 21** | Plain `main()`. No Spring — no request path to serve |
| Build | **Gradle (Kotlin DSL)** | Shadow jar, cached in CI |
| HTTP | **`java.net.http.HttpClient`** | JDK built-in |
| JSON | **Jackson Databind** | Only Java dependency in Phase 1 |
| Schedule | **GitHub Actions cron** | Weekly, free on public repos |
| Seed store | **Append-only JSONL in git** | The repo is the store |
| Frontend | **React 18 + Vite** | Plain `.jsx` |
| Charts | **Recharts** | Curve only; heatmap is CSS Grid |
| Host | **Cloudflare Pages** | Free, unlimited bandwidth, free TLS |
| DNS | **Cloudflare** | Domain registered at GoDaddy, nameservers pointed at Cloudflare |
| External API | **TomTom Routing API** (`calculateRoute`) | Freemium tier, no payment method |

### Phase 2 additions

| Layer | Choice | Why it's here |
|---|---|---|
| Framework | **Spring Boot 3 (Java 21)** | Real request path now exists |
| Web | `spring-boot-starter-web` | `@RestController` endpoints |
| Persistence | **Spring Data JPA** + Postgres | Samples table doubles as the cache |
| Migrations | **Flyway** | Schema versioned in the repo |
| Cache | **Caffeine** via `@Cacheable`, Postgres as durable layer | No Redis — see §9.3 |
| Rate limiting | **Bucket4j** + Postgres counter | Per-IP and global; survives scale-to-zero |
| Resilience | **Resilience4j** | Retry + circuit breaker on the TomTom API |
| Validation | `spring-boot-starter-validation` | `@Valid` on request bodies |
| Ops | **Spring Boot Actuator** | `/actuator/health` for Cloud Run probes |
| Startup | **GraalVM native image** (Spring AOT) | ~100ms cold start vs 10–20s JVM |
| DB host | **Neon** free Postgres | Autosuspends, wakes sub-second |
| App host | **Cloud Run**, us-central1 | Scale-to-zero, always-free tier |
| Edge | **Cloudflare** | Free rate limiting and DDoS shield |
| Deploy | GitHub Actions → Artifact Registry → Cloud Run | On push to main |

Dependency discipline: no Tailwind, no component library, no state manager, no date library, no Redis, no Lombok. Every dependency is something that can break a build six months from now.

**Do not add Spring Boot in Phase 1.** That phase is a cron job — read config, call HTTP, write files, exit. Spring's value (DI, servlet container, controllers, JPA) is unused for a 90-second process, and adding it signals not understanding what it's for. The v1→v2 split is itself the strongest thing about this project: a framework introduced when a framework became necessary.

---

## 5. TomTom Routing API

### 5.1 Which API and why not Google

Use **TomTom Routing API** (`calculateRoute`).

Google Routes API was the original choice and is a fine API, but Google requires an active billing account with a valid payment method before it will issue a key at all — even for usage that never leaves the free tier. That is a hard blocker for this project, whose entire premise is no recurring cost beyond the domain.

TomTom's freemium tier needs **no payment method**, permits commercial use, and is more generous than the Google allowance it replaces:

| | Google Routes (Compute Routes Pro) | TomTom Routing |
|---|---|---|
| Free allowance | 5,000 events/month | **20,000 requests/month** |
| Daily ceiling | — | 2,500 non-tile requests/day |
| Payment method | **Required** | Not required |

Do **not** use Google Directions or Distance Matrix as a fallback either — those moved to Legacy status on 2025-03-01 and cannot be enabled in new Cloud projects.

HERE was evaluated and rejected: its no-credit-card Limited plan was retired 2025-08-31.

### 5.2 The request

```
GET https://api.tomtom.com/routing/1/calculateRoute/{lat,lon}:{lat,lon}/json
      ?key=<key>
      &departAt=2026-09-02T15:00:00Z
      &travelMode=car
      &routeType=fastest
      &traffic=true
```

- **Waypoints live in the path**, as `lat,lon:lat,lon`. The colon is a path separator — URL-encoding it 404s the request. Encode the *query* values only.
- **`departAt` must be in the future**, ISO 8601. A bare local time is interpreted at the origin; send UTC with a `Z` and the ambiguity disappears.
- **A future `departAt` disables live traffic** and prices the trip off TomTom's historic speed-profile database instead. This is the behaviour the whole project rests on — and the thing §1 requires you to be honest about.
- `traffic=true` keeps the routing traffic-aware; with a future `departAt` that means historic, not live.

### 5.3 Response

```json
{
  "routes": [
    {
      "summary": {
        "lengthInMeters": 78234,
        "travelTimeInSeconds": 3120,
        "trafficDelayInSeconds": 420
      }
    }
  ]
}
```

Read `routes[0].summary.travelTimeInSeconds` and `lengthInMeters`. Both are plain integers — no unit suffix to parse. Handle an empty `routes` array, and a summary missing `travelTimeInSeconds`, without crashing.

### 5.4 Coordinates for seeded corridors (one-time, manual)

Corridors are `"lat,lon"` strings in `config/routes.json`. Right-click any point in Google Maps and it hands you the pair to copy. No place-ID collection, no autocomplete, no Places API in Phase 1.

The five committed corridors are Bay Area city-centre approximations. Refine them to specific addresses if a corridor's spread looks wrong.

### 5.5 Billing

Free tier: **20,000 routing requests/month**, 2,500/day, no card.

```
Phase 1 sampler:  5 routes × 7 days × 13 hours = 455/sweep × 4 = 1,820/month
Phase 2 lookups:  capped in-process, and cache-first in practice
```

That leaves ~18,000 requests/month of headroom — roughly 10× what the sampler consumes.

Departure times **cannot be batched**. A matrix endpoint batches origins × destinations but carries exactly one departure time per request, so each sampled time is its own billable call. Don't try to optimize this away.

## 6. Repo layout

```
/
├── config/routes.json
├── sampler/                        # Phase 1 — plain Java, no Spring
│   ├── build.gradle.kts
│   └── src/main/java/dev/<you>/forecast/
│       ├── Main.java
│       ├── RoutingClient.java
│       ├── DepartureSchedule.java
│       ├── SampleStore.java
│       ├── Aggregator.java
│       └── model/
├── service/                        # Phase 2 — Spring Boot
│   ├── build.gradle.kts
│   ├── Dockerfile
│   └── src/main/
│       ├── java/dev/<you>/forecast/api/
│       │   ├── ForecastApplication.java
│       │   ├── web/{RouteController,LookupController,QuotaController}.java
│       │   ├── service/{ForecastService,LookupService,QuotaService}.java
│       │   ├── client/RoutingClient.java
│       │   ├── repo/{SampleRepository,QuotaRepository}.java
│       │   ├── domain/{Sample,Corridor}.java
│       │   └── config/{CacheConfig,RateLimitFilter,CorsConfig}.java
│       └── resources/
│           ├── application.yml
│           └── db/migration/V1__init.sql
├── shared/                         # optional: model + Routes client shared by both
├── data/samples/<routeId>.jsonl
├── web/                            # Vite project root
│   ├── package.json
│   ├── vite.config.js
│   ├── index.html
│   ├── public/data/forecasts.json
│   └── src/
│       ├── main.jsx
│       ├── App.jsx
│       ├── components/{Heatmap,DepartureCurve,LeaveByPanel,RoutePicker,LookupPanel}.jsx
│       ├── lib/{colorScale.js,leaveBy.js,api.js}
│       └── styles.css
└── .github/workflows/
    ├── sample.yml                  # Phase 1
    └── deploy-service.yml          # Phase 2
```

`forecasts.json` lives in `web/public/data/` — Vite copies `public/` verbatim, so it serves at `/data/forecasts.json`. Get this path right in the sampler first, or you'll have a working build serving a 404.

**Cloudflare Pages config:** root directory `web`, build command `npm run build`, output directory `dist`. Pin `NODE_VERSION` in Pages environment variables.

---

## 7. Cost guardrails

### Phase 1

TomTom's freemium tier has **no payment method attached**, which changes the shape of this problem: an overrun cannot produce a bill, only rejected requests. The vendor ceiling is a hard wall rather than a meter. The guardrails below exist to make an overrun *loud and early* instead of a silently half-empty sweep.

1. **Sampler `MAX_CALLS` ceiling**, default 500, checked against the planned call count *before the first request* and aborting with a clear log line. This is the primary guardrail in Phase 1.
2. **Vendor ceilings** for reference: 2,500 requests/day and 20,000/month. One sweep is 455.
3. **~2 req/sec client-side throttle**, comfortably under the freemium QPS limit. A 455-call sweep takes about four minutes.
4. **Retry only 429 and 5xx**, max 3 attempts, exponential backoff. Never retry a 400 or 403 — those are a malformed coordinate or a bad key, and retrying only burns the monthly allowance against a request that cannot succeed.
5. Key in GitHub Secrets as `TOMTOM_API_KEY`. Never in the repo, never in `web/`. Locally it lives in a gitignored `.env`.

### Phase 2 — tighten before the first deploy

**Set an in-service daily lookup ceiling of 150/day.** With no vendor-side billing cap to fall back on, this application-level counter *is* the hard stop that makes the endpoint safe to expose.

- **Per-IP limit:** 5 lookups/day via Bucket4j.
- **Global daily counter** in Postgres with a kill switch. When tripped, `/api/lookup` serves cached results only and returns a flag the UI displays.
- **Cache-first, always.** Any bucket younger than 7 days is served from Postgres. Two users on the same corridor cost one API call.
- **Cloud Run:** `--max-instances=2 --cpu=1 --memory=512Mi --min-instances=0`.
- **Region must be us-central1, us-east1, or us-west1.** The Cloud Run free tier does not exist elsewhere, and it applies per billing account, not per service.
- **Cloudflare rate limiting rule** in front of the API hostname.
- API key injected as an env var from a secret store. Never baked into the image.

> **Open question for Phase 2, not Phase 1.** Cloud Run requires a GCP billing account with a card on file — the same blocker that moved this project off Google Routes. If that is still unacceptable when Phase 2 starts, re-evaluate the host then (Fly.io, Railway and Render all have card-free or card-light entry tiers, with the sleep/cold-start tradeoffs §2 warns about). Do not let this question delay Phase 1; the static site has no server at all.

---

## 8. Phase 1 — sampler and static site

### 8.1 `config/routes.json`

```json
[
  {
    "id": "sjsu-sf",
    "name": "SJSU → SF Financial District",
    "origin": "37.3352,-121.8811",
    "dest": "37.7946,-122.3999"
  }
]
```

Five entries. `id` must be filesystem-safe — it's used as a filename. Coordinates tolerate a space after the comma, so a pair pasted straight out of a map works.

### 8.2 `data/samples/<routeId>.jsonl`

One object per line, append-only, never rewritten:

```json
{"targetLocal":"2026-09-02T08:00","dayOfWeek":"WEDNESDAY","slotHour":8,"durationSeconds":2461,"distanceMeters":78234,"requestedAt":"2026-08-30T09:02:11Z"}
```

This is the project's real asset. **Never write a code path that truncates these files.** ~64 KB/week, ~3.3 MB/year.

### 8.3 `web/public/data/forecasts.json`

```json
{
  "generatedAt": "2026-08-30T09:05:00Z",
  "sweepsSampled": 6,
  "routes": [
    {
      "id": "sjsu-sf",
      "name": "SJSU → SF Financial District",
      "distanceMeters": 78234,
      "buckets": {
        "MONDAY": [ { "slotHour": 8, "medianSeconds": 2461, "n": 6 } ]
      }
    }
  ]
}
```

Median across all weeks for that (route, day, hour). Emit `null` for `medianSeconds` when a bucket has no samples — never `0`, which the UI would render as "instant trip."

### 8.4 Sampler behavior

1. Read `config/routes.json`.
2. Build slots: **next 7 calendar days**, `America/Los_Angeles`, 06:00–18:00 hourly → 91 slots per route.
3. Convert to UTC RFC 3339. **Use `ZonedDateTime` with the `America/Los_Angeles` zone, never a fixed offset** — DST transitions in March and November will silently shift every sample by an hour otherwise.
4. Check planned calls against `MAX_CALLS`; abort if over.
5. Call, append to JSONL.
6. Rebuild `forecasts.json`.
7. Log one summary line: calls, failures, rows appended.

Requirements:

- **Rate limit** ~2 req/sec. A 455-call sweep takes ~4 minutes.
- **Retry** 429 and 5xx with exponential backoff, max 3 attempts. Do **not** retry other 4xx — config errors, and retrying burns quota.
- **Partial failure is not fatal.** One bad coordinate pair shouldn't cost the sweep.
- **Exit non-zero only if every call failed.**
- **Median, not mean.**

Timing drift is a non-issue here: Actions can fire 15+ minutes late, but we query forecasts of future times, so a late run samples the same slots.

### 8.5 `sample.yml`

- `schedule: 0 9 * * 0` (Sunday 09:00 UTC ≈ 02:00 PT) plus `workflow_dispatch`. **Use the manual trigger for the first sweep** so the demo is data-complete immediately.
- `permissions: contents: write`, `timeout-minutes: 20`.
- checkout → setup-java 21 Temurin with Gradle cache → run shadow jar → commit and push if the diff is non-empty. Plain `git`, not a third-party action.

**GitHub disables scheduled workflows after 60 days of repo inactivity**, and `GITHUB_TOKEN` pushes may not reset that timer. Note it in the README and verify the workflow is enabled before applying anywhere.

---

## 9. Phase 2 — Spring Boot lookup service

### 9.1 Endpoints

```
GET  /api/routes                 seeded corridors
GET  /api/routes/{id}/forecast   heatmap data for a corridor
POST /api/lookup                 { origin, dest } as "lat,lon" → forecast
GET  /api/places/autocomplete    proxied Places autocomplete
GET  /api/quota                  remaining daily lookups
GET  /actuator/health            Cloud Run liveness
```

### 9.2 Schema (Flyway `V1__init.sql`)

```sql
CREATE TABLE corridor (
  id            BIGSERIAL PRIMARY KEY,
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
  day_of_week      SMALLINT NOT NULL,
  slot_hour        SMALLINT NOT NULL,
  duration_seconds INTEGER NOT NULL,
  distance_meters  INTEGER,
  requested_at     TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_sample_lookup ON sample (corridor_id, day_of_week, slot_hour, requested_at DESC);

CREATE TABLE daily_quota (
  day        DATE PRIMARY KEY,
  calls_made INTEGER NOT NULL DEFAULT 0
);
```

Seed `corridor` and `sample` from the Phase 1 JSONL on first deploy — a one-off import command, not a runtime dependency.

### 9.3 Caching — no Redis

The `sample` table **is** the cache. Lookup flow:

1. Resolve or create the corridor.
2. Query for buckets with `requested_at > now() - interval '7 days'`.
3. If a full grid exists → return it, zero API calls.
4. If not → check quota, call calculateRoute for the missing slots, persist, return.

Caffeine sits in front via `@Cacheable` for in-process hits, but it evaporates on scale-to-zero, so Postgres is the layer that matters. Adding Redis here would be a third cache tier serving no purpose and one more free tier to babysit.

**Budget note:** a cold corridor needs 91 calls for a full grid, which blows past a 150/day cap on the second lookup. For user lookups, sample a **reduced grid — weekdays only, 06:00–10:00 and 15:00–19:00, ~5 days × 9 hours = 45 calls** — and label it in the UI as a partial profile. Seeded corridors keep the full grid.

### 9.4 Rate limiting

- Bucket4j filter, 5 lookups/IP/day, keyed on `CF-Connecting-IP` (Cloudflare sits in front; `RemoteAddr` will be Cloudflare's).
- `QuotaService` increments `daily_quota` inside the same transaction as the API call. On exceeding the ceiling, throw a domain exception mapped to 429 with a body the UI can render.
- Cache hits do **not** count against either limit. Only actual TomTom calls do.

### 9.5 Address search proxy

`GET /api/places/autocomplete?q=` calls **TomTom Search (Fuzzy Search)** server-side and returns `{ lat, lon, description }` triples — coordinates, not place IDs, because coordinates are what `calculateRoute` consumes. The key never reaches the browser. Rate limit to 20/IP/day and debounce 300ms client-side. Search draws on its own freemium allowance, separate from Routing.

### 9.6 Native image

Build with Spring AOT + GraalVM. Expect to fight reflection config — register any Jackson-serialized DTO with `@RegisterReflectionForBinding`. **Do this last.** Get the service working as a plain JVM jar, deploy it, verify it, and only then convert. A broken native build on top of untested business logic is two problems at once.

Add a `native` Gradle task and a multi-stage Dockerfile. Cloud Run cold start should land near 100ms.

### 9.7 Deploy workflow

`deploy-service.yml`: on push to main touching `service/**` → build native image → push to Artifact Registry → `gcloud run deploy` with the flags in §7. Use Workload Identity Federation, not a long-lived service account key.

---

## 10. Frontend

React 18 + Vite, plain `.jsx`. **This is what an interviewer sees first — spend proportionally more time here than the line count suggests.**

**State:** `App.jsx` fetches `/data/forecasts.json` once in a `useEffect` and holds it in `useState`. Two pieces of UI state: `selectedRouteId`, `selectedDay`. No context, no reducer, no state library. Derived values go in `useMemo`, not state — mirroring props into state is the bug you'll otherwise write.

### 10.1 Heatmap (hero) — `Heatmap.jsx`

CSS Grid. Rows = Mon–Sun, columns = hours 06:00–18:00. Sequential scale (green → amber → red) from the route's fastest to slowest median.

- Interpolation in `lib/colorScale.js` as a pure function.
- Tooltip: day, hour, minutes, sample count.
- `medianSeconds === null` → explicit empty styling, never a zero-colored cell.
- Clicking a row sets `selectedDay`.
- Horizontal scroll at mobile width rather than squashed columns.
- **Label the best and worst cell directly on the grid.** This is what makes the screenshot readable without explanation.

Plain divs and CSS Grid — not Recharts. A heatmap is a grid of colored boxes.

### 10.2 Departure curve — `DepartureCurve.jsx`

Recharts `LineChart`, duration vs. hour for `selectedDay`. `ReferenceDot` on the minimum. Wrap in `ResponsiveContainer` **with an explicit parent height**, or Recharts renders at zero pixels and you'll lose an hour to a blank div.

### 10.3 Leave-by panel — `LeaveByPanel.jsx`

Walk buckets backward from a target arrival to find the latest departure that still arrives on time. Show it plus the penalty for the next two slots. Logic in `lib/leaveBy.js` as a pure function `(buckets, arrivalMinute) => result`. Unit-test it.

### 10.4 Lookup panel (Phase 2) — `LookupPanel.jsx`

Autocomplete for origin and destination against `/api/places/autocomplete`, then `POST /api/lookup`, rendering the result in the same `Heatmap` component.

**Must degrade gracefully.** All of these are normal states, not errors to hide:

- Backend unreachable → hide the panel, show the seeded heatmaps, no console noise.
- 429 → "You've used your 5 lookups today" with the reset time.
- Global quota tripped → "Live lookups paused until tomorrow; showing cached corridors."
- Partial grid → label it as a partial profile rather than rendering empty cells silently.

Display remaining quota from `/api/quota`. Visible limits read as intentional design; silent failures read as broken.

### 10.5 Presentation

- Landing page renders the heatmap immediately, zero interaction required.
- Footer: `generatedAt`, `sweepsSampled`, total sample count.
- Handle fetch failure — a blank white screen is worse than an error message.
- See the `frontend-design` guidance. A heatmap lives or dies on its color scale.

---

## 11. Build order

**Phase 1 — finish completely before starting Phase 2.**

1. **Prove the API works.** `sampler.jar probe` — one route, one future `departAt`, print the duration.
2. **Prove the premise.** Loop one route across 06:00–18:00 and print durations. **If there's no meaningful spread between best and worst hour, stop and reconsider** — flat corridors make the app pointless.
3. **Sampler + workflow**, then one manual sweep. Dataset is now complete.
4. **Aggregator** with tests.
5. **Scaffold Vite, deploy an almost-empty page** to Cloudflare Pages on the domain. Confirm the build works against three files, not thirty.
6. **Heatmap → curve → leave-by.**
7. **README.** Phase 1 is now a finished, presentable project.

**Phase 2**

8. Spring Boot skeleton, Flyway schema, one endpoint reading seeded data from Postgres. Run locally against Neon.
9. Import Phase 1 JSONL into Postgres.
10. `/api/lookup` with cache-first logic and the reduced grid.
11. Rate limiting, quota counter, kill switch.
12. Places autocomplete proxy.
13. Deploy to Cloud Run as a **plain JVM jar**. Verify end to end.
14. **Native image last.** Measure cold start before and after; put both numbers in the README.
15. `LookupPanel` in the frontend, with every degraded state handled.

---

## 12. Out of scope

Do not add unprompted:

- User accounts, auth, sessions
- Push notifications
- Redis
- Next.js, SSR, or anything needing Node at request time
- Tailwind, component libraries, state managers, date libraries
- Lombok
- Kubernetes, Terraform, Docker Compose for local dev
- Spring Boot in Phase 1

If a task seems to require one, stop and flag it rather than working around the constraint.

---

## 13. README talking points

- **Honest framing:** TomTom's future-`departAt` predictions are historical speed profiles; this reshapes them into a view neither TomTom nor Google Maps offers. It does not out-predict either.
- **Why Phase 1 has no framework and Phase 2 does.** A cron job doesn't need Spring; a rate-limited, cached, public API does. This is the single best thing to be able to explain.
- **Cost:** hard quota caps plus in-process ceilings rather than hoping. Include the arithmetic.
- **Why departure times can't be batched**, and what that implies for the sampling budget.
- **Cache design:** the samples table is the cache; why Redis would have been a third tier serving nothing.
- **Cold start:** measured JVM vs. native numbers.
- **DST correctness** via zone-aware time arithmetic.
- **Append-only history** as deliberate design.
- **Known limitation:** medians over a small number of weekly samples; low-`n` buckets are surfaced in the UI rather than hidden.

---

## 14. Cost summary

| Item | Cost |
|---|---|
| Domain (GoDaddy) | ~$22/yr at renewal |
| TomTom Routing API | $0 — 1,820 sampler calls + capped lookups, of 20,000 free/month |
| TomTom Search API | $0 — separate freemium allowance |
| GitHub Actions | $0 — unlimited on public repos |
| Cloudflare Pages, DNS, rate limiting | $0 |
| Cloud Run | $0 — well inside 2M requests / 180K vCPU-seconds |
| Neon Postgres | $0 |
| **Total recurring** | **domain only** |

**Phase 1 needs no payment method anywhere.** TomTom freemium, GitHub Actions, Cloudflare Pages and Cloudflare DNS are all card-free; the domain is the only thing you pay for. That is a stronger claim than the original Google-based design could make, and it is worth one line in the README.

Phase 2 reintroduces the question: Cloud Run requires a GCP billing account with a card, and has no hard spending cap — `--max-instances=2` plus Cloudflare rate limiting is what bounds worst-case exposure. See the note in §7.
