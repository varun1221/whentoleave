import { useMemo, useState } from "react";
import { leaveBy } from "../lib/leaveBy.js";
import { DAY_LABEL, clockLabel, hourLabel, toMinutes } from "../lib/format.js";

const DEFAULT_ARRIVAL = "09:00";

const toMinuteOfDay = (value) => {
  const [h, m] = value.split(":").map(Number);
  return Number.isFinite(h) && Number.isFinite(m) ? h * 60 + m : null;
};

export default function LeaveByPanel({ route, day }) {
  const [arrival, setArrival] = useState(DEFAULT_ARRIVAL);
  const arrivalMinute = toMinuteOfDay(arrival);

  const result = useMemo(
    () => (arrivalMinute == null ? null : leaveBy(route.buckets[day], arrivalMinute)),
    [route, day, arrivalMinute]
  );

  return (
    <section className="panel">
      <div className="panel-head">
        <h2>Leave by</h2>
        <label className="arrival-field">
          <span>Arrive at {DAY_LABEL[day]}</span>
          <input
            type="time"
            value={arrival}
            step={300}
            onChange={(e) => setArrival(e.target.value)}
          />
        </label>
      </div>

      {!result || (!result.onTime && !result.impossible) ? (
        <p className="empty-note">No samples for this day yet.</p>
      ) : result.onTime ? (
        <>
          <p className="leave-headline">
            Leave by <strong>{hourLabel(result.onTime.slotHour)}</strong>
            <span className="leave-detail">
              {" "}
              — {toMinutes(result.onTime.durationSeconds)} min, arriving around{" "}
              {clockLabel(result.onTime.arrivalMinute)}
            </span>
          </p>
          {result.alternatives.length > 0 && (
            <ul className="penalty-list">
              {result.alternatives.map((alt) => (
                <li key={alt.slotHour}>
                  <span className="penalty-slot">{hourLabel(alt.slotHour)}</span>
                  <span className="penalty-cost">
                    {toMinutes(alt.durationSeconds)} min drive, arrive{" "}
                    {clockLabel(alt.arrivalMinute)}
                  </span>
                  <span className="penalty-late">{alt.minutesLate} min late</span>
                </li>
              ))}
            </ul>
          )}
        </>
      ) : (
        <p className="leave-headline">
          No departure in the sampled window makes it.
          <span className="leave-detail">
            {" "}
            The best try is {hourLabel(result.impossible.slotHour)}, arriving{" "}
            {clockLabel(result.impossible.arrivalMinute)} —{" "}
            {result.impossible.minutesLate} min late.
          </span>
        </p>
      )}
    </section>
  );
}
