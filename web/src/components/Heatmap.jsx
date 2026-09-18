import { useState } from "react";
import { DAYS, DAY_LABEL, hourLabel, toMinutes } from "../lib/format.js";
import { colorFor, inkFor } from "../lib/colorScale.js";

/**
 * The hero view: day-of-week × hour, colored by median duration.
 *
 * Plain divs on a CSS Grid, not a chart library — a heatmap is a grid of colored
 * boxes, and Recharts would add a dependency to draw rectangles.
 */
export default function Heatmap({
  route,
  days = DAYS,
  hours,
  min,
  max,
  best,
  worst,
  ramp,
  emptyColor,
  selectedDay,
  onSelectDay,
  showNumbers,
}) {
  const [hovered, setHovered] = useState(null);

  return (
    <div className="heatmap">
      <div className="heatmap-scroll">
        <div
          className="grid"
          style={{ gridTemplateColumns: `var(--day-col) repeat(${hours.length}, 1fr)` }}
          role="table"
          aria-label={`Median driving time for ${route.name} by day and departure hour`}
        >
          {/* display:contents keeps the ARIA row grouping without disturbing the
              flat CSS Grid the cells are laid out on. */}
          <div className="aria-row" role="row">
            <div className="corner" role="presentation" />
            {hours.map((hour) => (
              <div key={hour} className="col-head" role="columnheader">
                {String(hour).padStart(2, "0")}
              </div>
            ))}
          </div>

          {/* The days this grid has, not a fixed week: a lookup is weekdays only. */}
          {days.map((day) => {
            const isSelected = day === selectedDay;
            return (
              <Row
                key={day}
                day={day}
                isSelected={isSelected}
                route={route}
                min={min}
                max={max}
                best={best}
                worst={worst}
                ramp={ramp}
                emptyColor={emptyColor}
                onSelectDay={onSelectDay}
                showNumbers={showNumbers}
                setHovered={setHovered}
              />
            );
          })}
        </div>
      </div>

      {hovered && (
        <div className="tooltip" role="status">
          <strong>
            {DAY_LABEL[hovered.day]} {hourLabel(hovered.slotHour)}
          </strong>
          {hovered.medianSeconds == null ? (
            <span className="tooltip-empty">no samples yet</span>
          ) : (
            <>
              <span className="tooltip-value">
                {toMinutes(hovered.medianSeconds)} min
              </span>
              <span className="tooltip-meta">
                median of {hovered.n} sample{hovered.n === 1 ? "" : "s"}
              </span>
            </>
          )}
        </div>
      )}
    </div>
  );
}

function Row({
  day,
  isSelected,
  route,
  min,
  max,
  best,
  worst,
  ramp,
  emptyColor,
  onSelectDay,
  showNumbers,
  setHovered,
}) {
  return (
    <div className="aria-row" role="row">
      <button
        type="button"
        role="rowheader"
        className={"row-head" + (isSelected ? " is-selected" : "")}
        onClick={() => onSelectDay(day)}
        aria-pressed={isSelected}
      >
        {DAY_LABEL[day]}
      </button>

      {route.buckets[day].map((bucket) => {
        const color = colorFor(bucket.medianSeconds, min, max, ramp);
        const isBest = best && best.day === day && best.slotHour === bucket.slotHour;
        const isWorst = worst && worst.day === day && worst.slotHour === bucket.slotHour;
        const empty = bucket.medianSeconds == null;

        return (
          <div
            key={bucket.slotHour}
            className={
              "cell" +
              (empty ? " is-empty" : "") +
              (isSelected ? " in-selected-row" : "") +
              (isBest || isWorst ? " is-marked" : "")
            }
            style={{
              background: color ?? emptyColor,
              color: color ? inkFor(color) : undefined,
            }}
            role="cell"
            tabIndex={0}
            onMouseEnter={() => setHovered({ day, ...bucket })}
            onMouseLeave={() => setHovered(null)}
            onFocus={() => setHovered({ day, ...bucket })}
            onBlur={() => setHovered(null)}
            onClick={() => onSelectDay(day)}
            aria-label={
              `${DAY_LABEL[day]} ${hourLabel(bucket.slotHour)}: ` +
              (empty
                ? "no samples"
                : `${toMinutes(bucket.medianSeconds)} minutes, ${bucket.n} samples`)
            }
          >
            {!empty && showNumbers && (
              <span className="cell-value">{toMinutes(bucket.medianSeconds)}</span>
            )}
            {/* Direct labels on the two cells that carry the whole story, so the
                screenshot reads without a caption. */}
            {isBest && <span className="marker">best</span>}
            {isWorst && <span className="marker">worst</span>}
          </div>
        );
      })}
    </div>
  );
}
