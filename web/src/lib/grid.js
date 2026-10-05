import { DAYS } from "./format.js";

/**
 * The range and the extremes of one forecast grid.
 *
 * A function rather than inline arithmetic in `App`, because the two grid shapes the UI
 * renders are not the same shape: a seeded corridor is seven days × 06:00–18:00, and a
 * lookup is weekdays × the configured peak hours. Walking a fixed Monday–Sunday list
 * over a lookup grid reads undefined rows, and reading the hours off `buckets.MONDAY`
 * assumes a day that a grid need not have.
 *
 * `empty` is the case worth naming: a cold lookup that filled nothing has no minimum,
 * and a zero would render as an instant trip in the brightest cell on the ramp.
 */
export function gridStats(grid) {
  const buckets = grid?.buckets ?? {};
  const days = DAYS.filter((day) => Array.isArray(buckets[day]));
  const hours = days.length === 0 ? [] : buckets[days[0]].map((b) => b.slotHour);

  let min = Infinity;
  let max = -Infinity;
  let best = null;
  let worst = null;

  for (const day of days) {
    for (const bucket of buckets[day]) {
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

  if (best == null) {
    return { days, hours, min: 0, max: 0, best: null, worst: null, empty: true };
  }
  return { days, hours, min, max, best, worst, empty: false };
}

/** The percentage between fastest and slowest, 0 when there is nothing to compare. */
export function spreadPercent(stats) {
  if (stats.empty || stats.min <= 0) return 0;
  return Math.round((100 * (stats.max - stats.min)) / stats.min);
}

/**
 * A day the given grid actually has, preferring the one already selected.
 *
 * Switching from a seeded corridor to a lookup while Saturday is selected would
 * otherwise hand the curve and the leave-by panel an undefined row.
 */
export function dayWithin(stats, preferred) {
  if (stats.days.includes(preferred)) return preferred;
  return stats.days[0] ?? null;
}

/** How many of a grid's hours have a sample, out of how many: how far a lookup got. */
export function fillProgress(grid) {
  const buckets = Object.values(grid.buckets).flat();
  return {
    filled: buckets.filter((bucket) => bucket.n > 0).length,
    total: buckets.length,
  };
}
