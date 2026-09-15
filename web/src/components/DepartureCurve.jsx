import {
  CartesianGrid,
  Line,
  LineChart,
  ReferenceDot,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { DAY_LABEL, hourLabel, toMinutes } from "../lib/format.js";

/**
 * Duration against departure hour for the selected day.
 *
 * ResponsiveContainer measures its parent, so the parent carries an explicit height
 * in CSS. Without one it measures zero and the chart renders as a blank div.
 */
export default function DepartureCurve({ route, day, accent }) {
  const data = route.buckets[day]
    .filter((b) => b.medianSeconds != null)
    .map((b) => ({
      hour: b.slotHour,
      minutes: toMinutes(b.medianSeconds),
      n: b.n,
    }));

  if (data.length === 0) {
    return (
      <p className="empty-note">
        No samples yet for {DAY_LABEL[day]}. The weekly sweep will fill this in.
      </p>
    );
  }

  const fastest = data.reduce((a, b) => (b.minutes < a.minutes ? b : a));

  // A zero baseline would squash the thing this chart exists to show: a swing from
  // 65 to 93 minutes is the whole point, and against 0-100 it reads as a flat line.
  // So the axis spans the day range plus padding, snapped to 5-minute gridlines.
  const lo = Math.min(...data.map((d) => d.minutes));
  const hi = Math.max(...data.map((d) => d.minutes));
  const pad = Math.max(3, Math.round((hi - lo) * 0.2));
  const domain = [
    Math.max(0, Math.floor((lo - pad) / 5) * 5),
    Math.ceil((hi + pad) / 5) * 5,
  ];

  // Above the dot is the natural place for this label, but at the first hour that puts
  // it on top of the y-axis ticks. The minimum lands at 06:00 on most corridors, so
  // that is the common case: push the label into the plot instead of over the axis.
  const labelPosition =
    fastest.hour === data[0].hour
      ? "right"
      : fastest.hour === data[data.length - 1].hour
        ? "left"
        : "top";

  return (
    <div className="curve-frame">
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={data} margin={{ top: 16, right: 16, bottom: 4, left: -8 }}>
          <CartesianGrid stroke="var(--grid)" vertical={false} />
          <XAxis
            dataKey="hour"
            tickFormatter={hourLabel}
            stroke="var(--text-muted)"
            tickLine={false}
            axisLine={{ stroke: "var(--grid)" }}
            fontSize={12}
          />
          <YAxis
            unit=" min"
            domain={domain}
            allowDecimals={false}
            stroke="var(--text-muted)"
            tickLine={false}
            axisLine={false}
            fontSize={12}
            width={62}
          />
          <Tooltip
            contentStyle={{
              background: "var(--surface-2)",
              border: "1px solid var(--border)",
              borderRadius: 8,
              color: "var(--text-primary)",
              fontSize: 13,
            }}
            labelFormatter={(h) => `${DAY_LABEL[day]} ${hourLabel(h)}`}
            formatter={(value, _name, item) => [
              `${value} min · ${item.payload.n} samples`,
              "Median",
            ]}
          />
          <Line
            type="monotone"
            dataKey="minutes"
            stroke={accent}
            strokeWidth={2}
            dot={{ r: 3, fill: accent, strokeWidth: 0 }}
            activeDot={{ r: 5, stroke: "var(--surface-1)", strokeWidth: 2 }}
            isAnimationActive={false}
          />
          <ReferenceDot
            x={fastest.hour}
            y={fastest.minutes}
            r={6}
            fill="var(--surface-1)"
            stroke={accent}
            strokeWidth={2}
            label={{
              value: `fastest ${fastest.minutes} min`,
              position: labelPosition,
              offset: 10,
              fill: "var(--text-secondary)",
              fontSize: 12,
            }}
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}
