import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Heatmap from "./components/Heatmap.jsx";
import ScaleLegend from "./components/ScaleLegend.jsx";
import RoutePicker from "./components/RoutePicker.jsx";
import DepartureCurve from "./components/DepartureCurve.jsx";
import LeaveByPanel from "./components/LeaveByPanel.jsx";
import LookupPanel from "./components/LookupPanel.jsx";
import Banner from "./components/Banner.jsx";
import Highlights from "./components/Highlights.jsx";
import {
  EMPTY_DARK,
  EMPTY_LIGHT,
  RAMP_DARK,
  RAMP_LIGHT,
} from "./lib/colorScale.js";
import { getQuota } from "./lib/api.js";
import { dayWithin, gridStats, spreadPercent } from "./lib/grid.js";
import { noticeFor } from "./lib/notices.js";
import { DAY_LABEL, formatDate, hourLabel } from "./lib/format.js";

/** A looked-up corridor's picker id, kept apart from the seeded slugs. */
const lookupId = (key) => `lookup:${key}`;

/** Tracks the viewer's theme so the heatmap uses the ramp built for that surface. */
function useDarkMode() {
  const [dark, setDark] = useState(
    () =>
      typeof window !== "undefined" &&
      window.matchMedia?.("(prefers-color-scheme: dark)").matches
  );
  useEffect(() => {
    const query = window.matchMedia?.("(prefers-color-scheme: dark)");
    if (!query) return undefined;
    const onChange = (e) => setDark(e.matches);
    query.addEventListener("change", onChange);
    return () => query.removeEventListener("change", onChange);
  }, []);
  return dark;
}

/**
 * Is the lookup service there?
 *
 * Phase 1 is a complete site on its own, so an absent backend is a normal state: the
 * seeded heatmaps render and the lookup panel is simply not offered (§10.4). That is why
 * this probe never sets the page-level error — a failed quota read hides one panel, it
 * does not break the page.
 */
function useLookupApi() {
  const [quota, setQuota] = useState(null);

  // Only the first probe decides whether the panel exists. A later refresh that fails
  // keeps the counters it last read, because unmounting the panel mid-interaction would
  // take the banner explaining a limit down with it — the visitor would see their lookup
  // vanish with no reason given, which is the failure §10.4 is about.
  const refresh = useCallback(() => getQuota().then(setQuota).catch(() => {}), []);

  useEffect(() => {
    refresh();
  }, [refresh]);
  return { quota, refresh };
}

export default function App() {
  const [forecasts, setForecasts] = useState(null);
  const [error, setError] = useState(null);
  const [selectedRouteId, setSelectedRouteId] = useState(null);
  const [selectedDay, setSelectedDay] = useState("WEDNESDAY");
  const [showNumbers, setShowNumbers] = useState(true);
  // Every corridor looked up this visit, oldest first, so a second lookup adds a tab
  // rather than taking the first one's away.
  const [lookups, setLookups] = useState([]);
  const routeRef = useRef(null);
  const dark = useDarkMode();
  const { quota, refresh: refreshQuota } = useLookupApi();

  useEffect(() => {
    let cancelled = false;
    fetch("/data/forecasts.json")
      .then((r) => {
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        return r.json();
      })
      .then((data) => {
        if (cancelled) return;
        setForecasts(data);
        setSelectedRouteId(data.routes[0]?.id ?? null);
      })
      .catch((e) => !cancelled && setError(e.message));
    return () => {
      cancelled = true;
    };
  }, []);

  // The looked-up grid is the same shape as a seeded one, so it joins the same picker
  // and flows through the same heatmap, curve and leave-by panel. It carries an id and a
  // name because the API has neither for a corridor nobody named.
  const routes = useMemo(
    () => [
      ...(forecasts?.routes ?? []),
      ...lookups.map(({ grid, key, name }) => ({
        ...grid,
        id: lookupId(key),
        name,
        lookedUp: true,
      })),
    ],
    [forecasts, lookups]
  );

  const route = useMemo(
    () => routes.find((r) => r.id === selectedRouteId) ?? null,
    [routes, selectedRouteId]
  );

  // Derived, not mirrored into state — the range and the extremes are functions of the
  // selected route and nothing else.
  const stats = useMemo(() => (route ? gridStats(route) : null), [route]);

  const onLookupResult = useCallback((result) => {
    // A corridor asked for again replaces its old tab in place, with the fresher grid.
    setLookups((prev) =>
      prev.some((l) => l.key === result.key)
        ? prev.map((l) => (l.key === result.key ? result : l))
        : [...prev, result]
    );
    setSelectedRouteId(lookupId(result.key));
    // The answer is below the search it came from: bring it into view rather than
    // leaving the visitor to find it.
    requestAnimationFrame(() =>
      routeRef.current?.scrollIntoView?.({ behavior: "smooth", block: "start" })
    );
  }, []);

  if (error) {
    return (
      <Frame>
        <p className="error">
          Couldn’t load the forecast data ({error}). The dataset is committed to the
          repo, so this is usually a deploy or path problem rather than an outage.
        </p>
      </Frame>
    );
  }

  if (!forecasts || !route || !stats) {
    return (
      <Frame>
        <p className="loading">Loading forecasts…</p>
      </Frame>
    );
  }

  const ramp = dark ? RAMP_DARK : RAMP_LIGHT;
  const emptyColor = dark ? EMPTY_DARK : EMPTY_LIGHT;
  // The curve takes the brand blue, not a ramp color: it is a line, not a heat reading.
  const accent = dark ? "#5b8def" : "#2563eb";
  // A weekday-only lookup has no Saturday row to select, so the day the panels below
  // read is clamped to one this grid actually has.
  const shownDay = dayWithin(stats, selectedDay) ?? selectedDay;
  // A degraded grid says why, and when the limit behind it lifts.
  const notice = noticeFor(route.notice, {
    dailyLimit: quota?.yourDailyLimit,
    resetsAt: route.resetsAt,
  });

  return (
    <Frame updated={formatDate(forecasts.generatedAt)}>
      <section className="hero">
        <p className="eyebrow">Bay Area commute forecasts</p>
        <h1>
          Pick the hour
          <br />
          <span className="hero-accent">not the traffic</span>
        </h1>
        <p className="standfirst">
          Google Maps tells you how long a trip takes <em>now</em>. This shows how it
          changes across the whole week, so you can choose when to go.
        </p>

        {/* Only once the service has answered: an unreachable backend offers no form. */}
        {quota && (
          <LookupPanel
            quota={quota}
            onResult={onLookupResult}
            onQuotaChange={refreshQuota}
          />
        )}
      </section>

      <section className="route-section" ref={routeRef}>
        <RoutePicker
          routes={routes}
          selectedRouteId={selectedRouteId}
          onSelect={setSelectedRouteId}
        />

        <div className="route-title">
          <h2>
            {route.name}
            {route.partial && <span className="badge">Weekday peaks</span>}
          </h2>
          <p className="subhead">
            {stats.empty
              ? "No samples yet"
              : `${spreadPercent(stats)}% slower at its worst than at its best`}
          </p>
        </div>

        <Banner notice={notice} />

        {!stats.empty && <Highlights route={route} stats={stats} />}

        <div className="card">
          <div className="card-head">
            <div>
              <h3>Week at a glance</h3>
              <p className="subhead">
                Median minutes by departure hour. Click a day to explore it below.
              </p>
            </div>
            <label className="switch">
              <input
                type="checkbox"
                role="switch"
                checked={showNumbers}
                onChange={(e) => setShowNumbers(e.target.checked)}
              />
              <span className="switch-track" aria-hidden="true" />
              <span>Show minutes</span>
            </label>
          </div>

          {route.partial && (
            <p className="hint">
              Looked-up routes are sampled at weekday peak hours only, so one lookup does
              not spend the whole day’s shared budget.
            </p>
          )}

          <Heatmap
            route={route}
            days={stats.days}
            hours={stats.hours}
            min={stats.min}
            max={stats.max}
            best={stats.best}
            worst={stats.worst}
            ramp={ramp}
            emptyColor={emptyColor}
            selectedDay={shownDay}
            onSelectDay={setSelectedDay}
            showNumbers={showNumbers}
          />
          <ScaleLegend min={stats.min} max={stats.max} ramp={ramp} />
        </div>

        <div className="split">
          <div className="card">
            <div className="card-head">
              <div>
                <h3>{DAY_LABEL[shownDay]} departure curve</h3>
                <p className="subhead">How the drive changes through the day</p>
              </div>
            </div>
            <DepartureCurve route={route} day={shownDay} accent={accent} />
          </div>

          <LeaveByPanel route={route} day={shownDay} />
        </div>
      </section>

      <footer className="colophon">
        <p>
          Medians across {forecasts.sweepsSampled} sweep
          {forecasts.sweepsSampled === 1 ? "" : "s"} · {forecasts.totalSamples} samples ·
          departure hours {hourLabel(stats.hours[0])}–
          {hourLabel(stats.hours[stats.hours.length - 1])} Pacific.
        </p>
        <p className="caveat">
          Historical averages from TomTom’s speed-profile data, reshaped into a view
          neither TomTom nor Google Maps offers. It does not out-predict either — it shows
          you the shape of the week they already know about.
          {forecasts.sweepsSampled < 3 && (
            <> Low sample counts this early; each cell’s <em>n</em> is in its tooltip.</>
          )}
        </p>
      </footer>
    </Frame>
  );
}

/** The top bar and page column every state renders inside. */
function Frame({ updated, children }) {
  return (
    <>
      <header className="topbar">
        <div className="topbar-inner">
          <a className="brand" href="/">
            <span className="brand-mark" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none">
                <circle cx="12" cy="12" r="8.5" stroke="currentColor" strokeWidth="2" />
                <path
                  d="M12 7.5V12l3 2"
                  stroke="currentColor"
                  strokeWidth="2"
                  strokeLinecap="round"
                />
              </svg>
            </span>
            When to Leave
          </a>
          {updated && <span className="topbar-meta">Updated {updated}</span>}
        </div>
      </header>
      <main className="shell">{children}</main>
    </>
  );
}
