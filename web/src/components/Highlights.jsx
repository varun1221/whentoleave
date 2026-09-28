import { DAY_LABEL, hourLabel, toMinutes } from "../lib/format.js";

/**
 * The answer before the evidence: the best and worst departures and what picking wrong
 * costs, readable at a glance before anyone parses a 91-cell grid.
 */
export default function Highlights({ route, stats }) {
  const penalty = toMinutes(stats.max) - toMinutes(stats.min);
  const slot = ({ day, slotHour }) => `${DAY_LABEL[day]} ${hourLabel(slotHour)}`;

  return (
    <dl className="highlights">
      <div className="stat is-best">
        <dt>Best time to leave</dt>
        <dd>
          <span className="stat-value">{slot(stats.best)}</span>
          <span className="stat-meta">{toMinutes(stats.min)} min drive</span>
        </dd>
      </div>
      <div className="stat is-worst">
        <dt>Worst time to leave</dt>
        <dd>
          <span className="stat-value">{slot(stats.worst)}</span>
          <span className="stat-meta">{toMinutes(stats.max)} min drive</span>
        </dd>
      </div>
      <div className="stat">
        <dt>Cost of bad timing</dt>
        <dd>
          <span className="stat-value">+{penalty} min</span>
          <span className="stat-meta">worst vs. best departure</span>
        </dd>
      </div>
      {route.distanceMeters != null && (
        <div className="stat">
          <dt>Distance</dt>
          <dd>
            <span className="stat-value">
              {(route.distanceMeters / 1609.34).toFixed(1)} mi
            </span>
            <span className="stat-meta">
              {(route.distanceMeters / 1000).toFixed(1)} km by road
            </span>
          </dd>
        </div>
      )}
    </dl>
  );
}
