import { useEffect, useMemo, useState } from "react";
import Heatmap from "./components/Heatmap.jsx";
import ScaleLegend from "./components/ScaleLegend.jsx";
import RoutePicker from "./components/RoutePicker.jsx";
import DepartureCurve from "./components/DepartureCurve.jsx";
import LeaveByPanel from "./components/LeaveByPanel.jsx";
import {
  EMPTY_DARK,
  EMPTY_LIGHT,
  RAMP_DARK,
  RAMP_LIGHT,
} from "./lib/colorScale.js";
import { DAYS, DAY_LABEL, formatDate, hourLabel } from "./lib/format.js";

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

export default function App() {
  const [forecasts, setForecasts] = useState(null);
  const [error, setError] = useState(null);
  const [selectedRouteId, setSelectedRouteId] = useState(null);
  const [selectedDay, setSelectedDay] = useState("WEDNESDAY");
  const [showNumbers, setShowNumbers] = useState(true);
  const dark = useDarkMode();

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

  const route = useMemo(
    () => forecasts?.routes.find((r) => r.id === selectedRouteId) ?? null,
    [forecasts, selectedRouteId]
  );

  // Derived, not mirrored into state — the range and the extremes are functions of
  // the selected route and nothing else.
  const stats = useMemo(() => {
    if (!route) return null;
    let min = Infinity;
    let max = -Infinity;
    let best = null;
    let worst = null;
    const hours = route.buckets.MONDAY.map((b) => b.slotHour);

    for (const day of DAYS) {
      for (const bucket of route.buckets[day]) {
        if (bucket.medianSeconds == null) continue;
        if (bucket.medianSeconds < min) {
          min = bucket.medianSeconds;
          best = { day, slotHour: bucket.slotHour };
        }
        if (bucket.medianSeconds > max) {
          max = bucket.medianSeconds;
          worst = { day, slotHour: bucket.slotHour };
        }
      }
    }
    if (best == null) return { hours, min: 0, max: 0, best: null, worst: null };
    return { hours, min, max, best, worst };
  }, [route]);

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
  const spread =
    stats.max > 0 ? Math.round((100 * (stats.max - stats.min)) / stats.min) : 0;

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
        routes={forecasts.routes}
        selectedRouteId={selectedRouteId}
        onSelect={setSelectedRouteId}
      />

      <section className="panel">
        <div className="panel-head">
          <div>
            <h2>{route.name}</h2>
            <p className="subhead">
              {(route.distanceMeters / 1000).toFixed(1)} km · fastest{" "}
              {Math.round(stats.min / 60)} min · slowest {Math.round(stats.max / 60)} min
              {spread > 0 && <> · {spread}% spread</>}
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

        <Heatmap
          route={route}
          hours={stats.hours}
          min={stats.min}
          max={stats.max}
          best={stats.best}
          worst={stats.worst}
          ramp={ramp}
          emptyColor={emptyColor}
          selectedDay={selectedDay}
          onSelectDay={setSelectedDay}
          showNumbers={showNumbers}
        />
        <ScaleLegend min={stats.min} max={stats.max} ramp={ramp} />
        <p className="hint">
          Click a day to load it into the curve and the leave-by panel below.
        </p>
      </section>

      <section className="panel">
        <div className="panel-head">
          <h2>
            {DAY_LABEL[selectedDay]} · departure curve
          </h2>
        </div>
        <DepartureCurve route={route} day={selectedDay} accent={accent} />
      </section>

      <LeaveByPanel route={route} day={selectedDay} />

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
