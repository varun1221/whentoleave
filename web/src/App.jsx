import { useCallback, useEffect, useMemo, useState } from "react";
import Heatmap from "./components/Heatmap.jsx";
import ScaleLegend from "./components/ScaleLegend.jsx";
import RoutePicker from "./components/RoutePicker.jsx";
import DepartureCurve from "./components/DepartureCurve.jsx";
import LeaveByPanel from "./components/LeaveByPanel.jsx";
import LookupPanel from "./components/LookupPanel.jsx";
import Banner from "./components/Banner.jsx";
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

/** The id the looked-up corridor takes in the picker, alongside the seeded slugs. */
const LOOKUP_ID = "your-lookup";

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
  const [lookup, setLookup] = useState(null);
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
  const routes = useMemo(() => {
    const seeded = forecasts?.routes ?? [];
    if (!lookup) return seeded;
    return [...seeded, { ...lookup.grid, id: LOOKUP_ID, name: lookup.name }];
  }, [forecasts, lookup]);

  const route = useMemo(
    () => routes.find((r) => r.id === selectedRouteId) ?? null,
    [routes, selectedRouteId]
  );

  // Derived, not mirrored into state — the range and the extremes are functions of the
  // selected route and nothing else.
  const stats = useMemo(() => (route ? gridStats(route) : null), [route]);

  const onLookupResult = useCallback(({ grid, name }) => {
    setLookup({ grid, name });
    setSelectedRouteId(LOOKUP_ID);
  }, []);

  if (error) {
    return (
      <main className="shell">
        <h1>When to Leave</h1>
        <p className="error">
          Couldn’t load the forecast data ({error}). The dataset is committed to the
          repo, so this is usually a deploy or path problem rather than an outage.
        </p>
      </main>
    );
  }

  if (!forecasts || !route || !stats) {
    return (
      <main className="shell">
        <h1>When to Leave</h1>
        <p className="loading">Loading forecasts…</p>
      </main>
    );
  }

  const ramp = dark ? RAMP_DARK : RAMP_LIGHT;
  const emptyColor = dark ? EMPTY_DARK : EMPTY_LIGHT;
  const accent = ramp[Math.floor(ramp.length / 2)];
  const spread = spreadPercent(stats);
  // A weekday-only lookup has no Saturday row to select, so the day the panels below
  // read is clamped to one this grid actually has.
  const shownDay = dayWithin(stats, selectedDay) ?? selectedDay;
  // A degraded grid says why, and when the limit behind it lifts.
  const notice = noticeFor(route.notice, {
    dailyLimit: quota?.yourDailyLimit,
    resetsAt: route.resetsAt,
  });

  return (
    <main className="shell">
      <header className="masthead">
        <h1>When to Leave</h1>
        <p className="standfirst">
          Google Maps tells you how long a trip takes <em>now</em>. This shows how it
          changes across the week, so you can pick the hour instead of accepting it.
        </p>
      </header>

      <RoutePicker
        routes={routes}
        selectedRouteId={selectedRouteId}
        onSelect={setSelectedRouteId}
      />

      <section className="panel">
        <div className="panel-head">
          <div>
            <h2>
              {route.name}
              {route.partial && <span className="badge">partial profile</span>}
            </h2>
            <p className="subhead">
              {route.distanceMeters != null && (
                <>{(route.distanceMeters / 1000).toFixed(1)} km · </>
              )}
              {stats.empty ? (
                <>no samples yet</>
              ) : (
                <>
                  fastest {Math.round(stats.min / 60)} min · slowest{" "}
                  {Math.round(stats.max / 60)} min
                  {spread > 0 && <> · {spread}% spread</>}
                </>
              )}
            </p>
          </div>
          <label className="toggle">
            <input
              type="checkbox"
              checked={showNumbers}
              onChange={(e) => setShowNumbers(e.target.checked)}
            />
            <span>Show minutes</span>
          </label>
        </div>

        <Banner notice={notice} />

        {route.partial && (
          <p className="hint">
            A looked-up corridor is sampled at weekday peak hours only — five days × nine
            hours instead of the full 91-cell grid, which would spend the day’s shared
            budget on a single lookup.
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
        <p className="hint">
          Click a day to load it into the curve and the leave-by panel below.
        </p>
      </section>

      {/* Only once the service has answered: an unreachable backend offers no form. */}
      {quota && (
        <LookupPanel
          quota={quota}
          onResult={onLookupResult}
          onQuotaChange={refreshQuota}
        />
      )}

      <section className="panel">
        <div className="panel-head">
          <h2>
            {DAY_LABEL[shownDay]} · departure curve
          </h2>
        </div>
        <DepartureCurve route={route} day={shownDay} accent={accent} />
      </section>

      <LeaveByPanel route={route} day={shownDay} />

      <footer className="colophon">
        <p>
          Medians across {forecasts.sweepsSampled} sweep
          {forecasts.sweepsSampled === 1 ? "" : "s"} · {forecasts.totalSamples} samples ·
          last updated {formatDate(forecasts.generatedAt)} · departure hours{" "}
          {hourLabel(stats.hours[0])}–{hourLabel(stats.hours[stats.hours.length - 1])}{" "}
          Pacific.
        </p>
        <p className="caveat">
          These are historical averages from TomTom’s speed-profile data, reshaped into
          a view neither TomTom nor Google Maps offers. It does not out-predict either —
          it shows you the shape of the week they already know about.
          {forecasts.sweepsSampled < 3 && (
            <> Low sample counts this early; each cell’s <em>n</em> is in its tooltip.</>
          )}
        </p>
      </footer>
    </main>
  );
}
