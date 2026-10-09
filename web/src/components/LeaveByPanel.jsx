import { useMemo, useState } from "react";
import { BUFFER_MINUTES, leaveBy } from "../lib/leaveBy.js";
import { DAY_LABEL, clockLabel, toMinutes } from "../lib/format.js";

const DEFAULT_ARRIVAL = "09:00";

const toMinuteOfDay = (value) => {
  const [h, m] = value.split(":").map(Number);
  return Number.isFinite(h) && Number.isFinite(m) ? h * 60 + m : null;
};

/**
 * "I need to arrive by ___ on ___" filled in as a sentence, answered with a clock time.
 * The day buttons share state with the heatmap rows, so picking either moves both.
 */
export default function LeaveByPanel({ route, day, days, onSelectDay }) {
  const [arrival, setArrival] = useState(DEFAULT_ARRIVAL);
  const arrivalMinute = toMinuteOfDay(arrival);

  const result = useMemo(
    () => (arrivalMinute == null ? null : leaveBy(route.buckets[day], arrivalMinute)),
    [route, day, arrivalMinute]
  );

  return (
    <section className="card leave-by">
      <div className="card-head">
        <div>
          <h3>Leave by</h3>
          <p className="subhead">The latest you can leave and still arrive on time</p>
        </div>
      </div>

      <div className="leave-question">
        <label className="arrival-field">
          <span>I need to arrive by</span>
          <input
            type="time"
            value={arrival}
            step={300}
            onChange={(e) => setArrival(e.target.value)}
          />
        </label>
        <div className="day-picker" role="group" aria-label="Day">
          <span>on</span>
          {days.map((d) => (
            <button
              key={d}
              type="button"
              className={d === day ? "day-chip is-selected" : "day-chip"}
              aria-pressed={d === day}
              onClick={() => onSelectDay(d)}
            >
              {DAY_LABEL[d]}
            </button>
          ))}
        </div>
      </div>

      <div className="leave-answer" aria-live="polite">
        {!result || (!result.onTime && !result.impossible) ? (
          <p className="empty-note">
            {arrivalMinute == null
              ? "Pick an arrival time."
              : `No samples for ${DAY_LABEL[day]} yet.`}
          </p>
        ) : result.onTime ? (
          <OnTime result={result} />
        ) : (
          <p className="leave-headline">
            <span className="leave-miss">Nothing makes it in time</span>
            <span className="leave-detail">
              The closest is leaving at {clockLabel(result.impossible.departMinute)},
              arriving about {clockLabel(result.impossible.arrivalMinute)} —{" "}
              <span className="leave-late">{result.impossible.minutesLate} min late</span>.
            </span>
          </p>
        )}
      </div>
    </section>
  );
}

function OnTime({ result: { onTime, buffer } }) {
  return (
    <>
      <p className="leave-headline">
        <span className="leave-label">Leave by</span>
        <span className="leave-time">{clockLabel(onTime.departMinute)}</span>
        <span className="leave-detail">
          {toMinutes(onTime.durationSeconds)} min drive, arriving about{" "}
          {clockLabel(onTime.arrivalMinute)}
        </span>
      </p>

      {buffer && (
        <p className="leave-buffer">
          <span>Want {BUFFER_MINUTES} min to spare?</span>{" "}
          Leave by <strong>{clockLabel(buffer.departMinute)}</strong>, arriving about{" "}
          {clockLabel(buffer.arrivalMinute)}.
        </p>
      )}

      {onTime.lastSampled && (
        <p className="leave-note">
          That’s the latest sampled departure. Nothing after it was sampled, so leaving
          later might still work.
        </p>
      )}
    </>
  );
}
