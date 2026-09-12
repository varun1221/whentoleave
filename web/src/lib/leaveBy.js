/**
 * "I need to be there by 09:00 — when do I leave?"
 *
 * Walks the day's buckets backward from the deadline and returns the latest departure
 * that still lands on time, plus what the next two slots cost you. Pure: takes
 * buckets and a target arrival, returns a result. No dates, no clock, no fetch.
 */

/** Minutes past midnight when a departure at this slot would arrive. */
const arrivalOf = (bucket) => bucket.slotHour * 60 + bucket.medianSeconds / 60;

/**
 * @param buckets one day's cells: [{ slotHour, medianSeconds, n }], medianSeconds may be null
 * @param arrivalMinute target arrival, minutes past midnight (09:00 → 540)
 * @returns {{
 *   onTime: object|null,      the latest departure that arrives by the deadline
 *   alternatives: object[],   the next two slots, each with minutesLate
 *   impossible: object|null   when even the first slot misses, the closest attempt
 * }}
 */
export function leaveBy(buckets, arrivalMinute) {
  const sampled = (buckets ?? [])
    .filter((b) => b.medianSeconds != null)
    .slice()
    .sort((a, b) => a.slotHour - b.slotHour);

  if (sampled.length === 0) {
    return { onTime: null, alternatives: [], impossible: null };
  }

  const withArrival = sampled.map((b) => ({
    slotHour: b.slotHour,
    durationSeconds: b.medianSeconds,
    n: b.n,
    arrivalMinute: arrivalOf(b),
  }));

  // The latest slot that still makes it — not the first. A later departure can be
  // faster than an earlier one (leaving after the peak often is), and picking the
  // first match would hand back an unnecessarily early alarm.
  const onTimeCandidates = withArrival.filter((b) => b.arrivalMinute <= arrivalMinute);
  const onTime =
    onTimeCandidates.length > 0
      ? onTimeCandidates[onTimeCandidates.length - 1]
      : null;

  if (!onTime) {
    // Nothing works. Report the option that misses by the least.
    const closest = withArrival.reduce((best, b) =>
      b.arrivalMinute < best.arrivalMinute ? b : best
    );
    return {
      onTime: null,
      alternatives: [],
      impossible: {
        ...closest,
        minutesLate: Math.round(closest.arrivalMinute - arrivalMinute),
      },
    };
  }

  const after = withArrival.filter((b) => b.slotHour > onTime.slotHour).slice(0, 2);

  return {
    onTime,
    alternatives: after.map((b) => ({
      ...b,
      minutesLate: Math.round(b.arrivalMinute - arrivalMinute),
    })),
    impossible: null,
  };
}
