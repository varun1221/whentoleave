import { toMinutes } from "../lib/format.js";

/**
 * A semantic-heat ramp is only legible with a legend — green/amber/red is a
 * convention, not a self-evident order, so the mapping gets stated outright.
 */
export default function ScaleLegend({ min, max, ramp }) {
  return (
    <div className="legend">
      <span className="legend-label">{toMinutes(min)} min</span>
      <div
        className="legend-bar"
        style={{ background: `linear-gradient(to right, ${ramp.join(", ")})` }}
        role="img"
        aria-label={`Color scale from ${toMinutes(min)} minutes to ${toMinutes(max)} minutes`}
      />
      <span className="legend-label">{toMinutes(max)} min</span>
    </div>
  );
}
