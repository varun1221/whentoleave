/**
 * "I need to be there by 09:00 — when do I leave?"
 *
 * Answers to the minute, not the hour. Between two adjacent sampled hours the drive
 * time is taken to change linearly, so a 25-minute drive at 08:00 and a 35-minute
 * drive at 09:00 put the latest on-time departure for a 09:00 deadline at about 08:28.
 *
 * Where nothing was sampled — a lookup's midday gap, the evening after the last
 * sample, the early morning before the first — the answer is an estimate, not a
 * fallback to the last sampled hour. Falling back would tell someone with a 15:00
 * appointment to leave at 10:00 and wait four hours. The estimate assumes the slower
 * of the sampled drives either side of the stretch: the off-peak hours a gap holds are
 * rarely slower than the peaks around them, so it errs toward arriving early, not late.
 *
 * Pure: takes buckets and a target arrival, returns a result. No dates, no clock, no
 * fetch.
 */

/** How much earlier the "with time to spare" answer aims to arrive. */
export const BUFFER_MINUTES = 15;

/** Estimates are rounded down to this, so they don't claim a precision they lack. */
const ESTIMATE_STEP = 5;

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
function departure(departMinute, minutes) {
  return {
    departMinute,
    durationSeconds: minutes * 60,
    arrivalMinute: departMinute + minutes,
  };
}

/**
 * The latest sampled departure minute that arrives by `deadline`, or null.
 *
 * Arrival time is linear between two adjacent sampled hours, so within each such hour
 * the on-time departures form one interval and its end is found by solving a line.
 * The latest candidate across every hour wins — not the first: a later departure can
 * be faster than an earlier one (leaving after the peak often is), and stopping at the
 * first match would hand back an unnecessarily early alarm.
 */
function latestSampled(samples, deadline) {
  let best = null;
  for (const s of samples) {
    const start = s.hour * 60;
    const arriveAtStart = start + s.minutes;
    if (arriveAtStart > deadline) continue;
    best = Math.max(best ?? start, start);

    const next = samples.find((n) => n.hour === s.hour + 1);
    if (!next) continue;
    const arriveAtEnd = start + 60 + next.minutes;
    if (deadline < arriveAtEnd) {
      // Floor, so rounding never turns an on-time answer into a late one.
      const crossing = Math.floor(
        start + ((deadline - arriveAtStart) / (arriveAtEnd - arriveAtStart)) * 60
      );
      best = Math.max(best, crossing);
    }
  }
  return best == null ? null : departure(best, durationAt(samples, best));
}

/**
 * The stretches of departure time with no sample: before the first sampled hour,
 * between two that are not adjacent, and after the last. Each carries the sampled
 * hours either side and the drive time assumed across it.
 */
function unsampledStretches(samples) {
  const first = samples[0];
  const last = samples[samples.length - 1];
  const stretches = [
    { from: 0, to: first.hour * 60, after: null, before: first.hour, minutes: first.minutes },
  ];
  samples.forEach((s, i) => {
    const next = samples[i + 1];
    if (next && next.hour - s.hour > 1) {
      stretches.push({
        from: s.hour * 60,
        to: next.hour * 60,
        after: s.hour,
        before: next.hour,
        minutes: Math.max(s.minutes, next.minutes),
      });
    }
  });
  stretches.push({
    from: last.hour * 60,
    to: 24 * 60,
    after: last.hour,
    before: null,
    minutes: last.minutes,
  });
  return stretches;
}

/**
 * The latest estimated departure that arrives by `deadline`, or null. Only a departure
 * strictly inside an unsampled stretch counts: on a sampled hour the samples answer.
 */
function latestEstimated(samples, deadline) {
  let best = null;
  for (const stretch of unsampledStretches(samples)) {
    const depart =
      Math.floor((deadline - stretch.minutes) / ESTIMATE_STEP) * ESTIMATE_STEP;
    const inside = stretch.after == null ? depart >= stretch.from : depart > stretch.from;
    if (!inside || depart >= stretch.to) continue;
    if (best == null || depart > best.departMinute) {
      best = {
        ...departure(depart, stretch.minutes),
        estimate: { after: stretch.after, before: stretch.before },
      };
    }
  }
  return best;
}

/**
 * The latest departure that arrives by `deadline`, sampled or estimated, or null.
 * `estimate` is null on a sampled answer; on an estimated one it names the sampled
 * hours either side of the gap it was read across (null for an open end).
 */
function latestDeparture(samples, deadline) {
  const sampled = latestSampled(samples, deadline);
  const estimated = latestEstimated(samples, deadline);
  if (estimated && (!sampled || estimated.departMinute > sampled.departMinute)) {
    return estimated;
  }
  return sampled && { ...sampled, estimate: null };
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
 *   buffer also carry `estimate`, impossible carries `minutesLate`.
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
      .map((s) => departure(s.hour * 60, s.minutes))
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
