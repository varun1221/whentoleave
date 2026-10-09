/**
 * "I need to be there by 09:00 — when do I leave?"
 *
 * Answers to the minute, not the hour. Between two adjacent sampled hours the drive
 * time is taken to change linearly, so a 25-minute drive at 08:00 and a 35-minute
 * drive at 09:00 put the latest on-time departure for a 09:00 deadline at about 08:28.
 * Hours with a gap between them are never bridged: a lookup sampled at 09:00 and
 * 16:00 says nothing about noon. Pure: takes buckets and a target arrival, returns a
 * result. No dates, no clock, no fetch.
 */

/** How much earlier the "with time to spare" answer aims to arrive. */
export const BUFFER_MINUTES = 15;

/** Drive time in minutes for a departure at `minute`, read off the samples. */
function durationAt(samples, minute) {
  const exact = samples.find((s) => s.hour * 60 === minute);
  if (exact) return exact.minutes;
  const before = samples.find((s) => s.hour === Math.floor(minute / 60));
  const after = samples.find((s) => s.hour === before.hour + 1);
  const t = (minute - before.hour * 60) / 60;
  return before.minutes + (after.minutes - before.minutes) * t;
}

/** A departure described the way the panel shows it. */
function departure(samples, departMinute) {
  const minutes = durationAt(samples, departMinute);
  return {
    departMinute,
    durationSeconds: minutes * 60,
    arrivalMinute: departMinute + minutes,
  };
}

/**
 * The latest departure minute that arrives by `deadline`, or null when none does.
 *
 * Arrival time is linear between two adjacent sampled hours, so within each such hour
 * the on-time departures form one interval and its end is found by solving a line.
 * The latest candidate across every hour wins — not the first: a later departure can
 * be faster than an earlier one (leaving after the peak often is), and stopping at the
 * first match would hand back an unnecessarily early alarm.
 */
function latestDeparture(samples, deadline) {
  let best = null;
  for (const s of samples) {
    const start = s.hour * 60;
    const arriveAtStart = start + s.minutes;
    if (arriveAtStart <= deadline) best = Math.max(best ?? start, start);

    const next = samples.find((n) => n.hour === s.hour + 1);
    if (!next) continue;
    const arriveAtEnd = start + 60 + next.minutes;
    if (arriveAtStart <= deadline && deadline < arriveAtEnd) {
      // Floor, so rounding never turns an on-time answer into a late one.
      const crossing = Math.floor(
        start + ((deadline - arriveAtStart) / (arriveAtEnd - arriveAtStart)) * 60
      );
      best = Math.max(best, crossing);
    }
  }
  if (best == null) return null;

  const result = departure(samples, best);
  const onHour = best % 60 === 0;
  return {
    ...result,
    // Sitting on a sampled hour with nothing sampled after it: later departures might
    // also make it, but there is no data to say so.
    lastSampled:
      onHour &&
      result.arrivalMinute < deadline &&
      !samples.some((n) => n.hour === best / 60 + 1),
  };
}

/**
 * @param buckets one day's cells: [{ slotHour, medianSeconds, n }], medianSeconds may be null
 * @param arrivalMinute target arrival, minutes past midnight (09:00 → 540)
 * @returns {{
 *   onTime: object|null,      the latest departure that arrives by the deadline
 *   buffer: object|null,      the latest that arrives BUFFER_MINUTES early
 *   impossible: object|null   when nothing makes it, the attempt that misses by least
 * }}
 *   Each departure is { departMinute, durationSeconds, arrivalMinute }; onTime and
 *   buffer also carry `lastSampled`, impossible carries `minutesLate`.
 */
export function leaveBy(buckets, arrivalMinute) {
  const samples = (buckets ?? [])
    .filter((b) => b.medianSeconds != null)
    .map((b) => ({ hour: b.slotHour, minutes: b.medianSeconds / 60 }))
    .sort((a, b) => a.hour - b.hour);

  if (samples.length === 0) {
    return { onTime: null, buffer: null, impossible: null };
  }

  const onTime = latestDeparture(samples, arrivalMinute);

  if (!onTime) {
    // Arrival is linear between samples, so the earliest arrival is at a sampled hour.
    const closest = samples
      .map((s) => departure(samples, s.hour * 60))
      .reduce((best, d) => (d.arrivalMinute < best.arrivalMinute ? d : best));
    return {
      onTime: null,
      buffer: null,
      impossible: {
        ...closest,
        minutesLate: Math.round(closest.arrivalMinute - arrivalMinute),
      },
    };
  }

  const buffer = latestDeparture(samples, arrivalMinute - BUFFER_MINUTES);
  return {
    onTime,
    buffer: buffer && buffer.departMinute < onTime.departMinute ? buffer : null,
    impossible: null,
  };
}
