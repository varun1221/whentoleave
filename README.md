# When to Leave

**Google Maps tells you how long a trip takes right now. When to Leave tells you when to go.**

[**whentoleave.me**](https://whentoleave.me) · [Design](docs/DESIGN.md) ·
[Developing](docs/DEVELOPING.md) · [Deploying](docs/DEPLOYING.md)

When to Leave shows how driving time on a route changes across the whole week, hour by
hour, in one colour-coded grid. Leaving San Francisco for Oakland at 06:00 costs 17
minutes. At 17:00 on a Wednesday the same trip costs 36. You can see that at a glance,
for every hour of every day, instead of checking a map app over and over.

It runs on its own. A weekly job refreshes the data, the site and the API redeploy
themselves.

| Step | What you do | What you get |
|---|---|---|
| **01** | Pick a route | Five Bay Area commutes are ready to go, or look up any two addresses |
| **02** | Read the week | A day × hour heatmap, green to red, with the best and worst times marked |
| **03** | Set your deadline | The latest departure that still gets you there, and what leaving later costs |

---

## Features

**The whole week at a glance.** A 7-day × 13-hour grid (06:00–18:00) for each commute.

**Departure curve.** Click a day to see how its travel time rises and falls hour by
hour, and where the rush hours start and end.

**Leave-by planner.** Enter the time you need to arrive. You get the latest departure
that makes it, plus the next two options and how many minutes late each would make you.

**Any route, on demand.** Search two addresses with autocomplete, or paste
coordinates, and get a weekday profile of the peak hours for that trip. It opens as its
own tab next to the built-in commutes.

**Self-updating.** Every Sunday, GitHub Actions samples the week ahead, commits the
data, loads it into the database, and the site redeploys.

---

## The problem it solves

| Without it | With When to Leave |
|---|---|
| ❌ Maps says 36 minutes, and you can't tell whether that's normal for 5pm on a Wednesday or today is unusual. | ✅ The whole week is on one screen, so you know what normal looks like for every hour. |
| ❌ You know rush hour is bad, but not how much leaving 30 minutes earlier would save. | ✅ The departure curve shows what every hour costs. |
| ❌ You need to be there by 9:00, so you guess a departure time and pad it. | ✅ The planner gives the latest departure that still gets you there on time. |

---

## How it works

```
                 every Sunday
GitHub Actions ─────────────────► Java sampler ──► TomTom Routing API
                                     │               (future departure times)
                                     ▼
                          data/samples/*.jsonl  ──►  forecasts.json
                          (append-only history)          │
                                     │                    ▼
                                     │         Cloudflare Pages ──► whentoleave.me
                                     ▼
                              Neon Postgres ◄──── Cloud Run: Spring Boot API
                                                  (api.whentoleave.me, behind Cloudflare)
                                                        │
                                           lookups for any route, cache-first,
                                           rate-limited, TomTom Routing + Search
```

- **The five built-in commutes need no server at all.** A weekly job asks TomTom for
  every hour of the coming week (5 routes × 7 days × 13 hours = 455 calls) and writes a
  static JSON file the site reads.
- **Custom routes go through a small API.** It serves anything sampled in the last week
  straight from the database, and only calls TomTom for the hours it doesn't have yet.
- **The same weekly job keeps both in sync.** Each sweep lands in the repo for the site
  and in Postgres for the API.

The full architecture, the cost arithmetic and the guardrails are in
[docs/DESIGN.md](docs/DESIGN.md).

---

## Quickstart

See the site running locally with the committed data, **no API key needed**:

```bash
git clone https://github.com/varun1221/forecastapp.git
cd forecastapp/web
npm install
npm run dev          # http://localhost:5173
```

To sample fresh data yourself, get a free TomTom key (about two minutes, no credit card)
at [developer.tomtom.com](https://developer.tomtom.com):

```bash
cp .env.example .env                              # paste your key in
set -a; source .env; set +a
./gradlew :sampler:shadowJar
java -jar sampler/build/libs/sampler.jar probe    # 1 call, checks the key works
java -jar sampler/build/libs/sampler.jar sweep    # 455 calls, the full grid
```

Requirements: JDK 21, Node 20+, and Postgres 16 for the API and its tests.
[docs/DEVELOPING.md](docs/DEVELOPING.md) covers running the API locally, the test
database, and how to trigger each limit by hand.

---

## Development

```bash
./gradlew build                  # sampler + API, with tests
./gradlew :service:bootRun       # the API on :8080
cd web && npm run dev            # the site on :5173, proxying /api to :8080
cd web && npm test               # frontend tests
cd web && npm run build          # production build
```

---

## FAQ

**Where does the data come from?** TomTom's Routing API, asked about departure times
in the coming week. For a future time, TomTom answers from its historical speed
profiles, which is exactly the "normal for this hour" picture this app is built on.

**How fresh is it?** A sweep runs every Sunday at 02:00 Pacific. The site and the API
both pick it up automatically.

**Why does a custom route only get weekday peak hours?** Every departure time is a
separate billable API call, and no endpoint batches them. A full week is 91 calls per
route; the weekday peaks (5 days × 9 hours) are 45, and they're where commute times
actually differ.


---

## Roadmap

- ✅ Weekly sampler and the full-week heatmap for five commutes
- ✅ Departure curve and leave-by planner
- ✅ Custom route lookups with address search
- ✅ Per-visitor limits, global ceilings and a kill switch
- ✅ Fully automated weekly refresh of the site and the database
- ⚪ Month-over-month drift view (the append-only history makes it possible)
- ⚪ Built-in commutes pinned to specific addresses instead of city centres

---

## License

MIT © 2026 Varun Venkatesh. See [LICENSE](LICENSE).
