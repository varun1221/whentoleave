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
            unit="m"
            stroke="var(--text-muted)"
            tickLine={false}
            axisLine={false}
            fontSize={12}
            width={48}
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
              value: `fastest ${fastest.minutes}m`,
              position: "top",
              fill: "var(--text-secondary)",
              fontSize: 12,
            }}
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}
