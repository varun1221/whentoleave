/**
 * Grid fixtures shared by the component tests.
 *
 * Here rather than in one test file because more than one component has to survive the
 * shape `POST /api/lookup` returns, and a fixture copied per test file drifts from the
 * real response the moment the response changes.
 */

/** Matches forecast.lookup.weekday-hours: weekday peaks, with the midday gap. */
export const WEEKDAY_HOURS = [6, 7, 8, 9, 10, 15, 16, 17, 18];

export const WEEKDAYS = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"];

const bucket = (slotHour, minutes) => ({
  slotHour,
  medianSeconds: minutes == null ? null : minutes * 60,
  n: minutes == null ? 0 : 3,
});

/**
 * The shape `POST /api/lookup` returns: weekdays only, peak hours only, no slug or label,
 * and a distance that is null until some slot has been filled.
 *
 * @param minutesAt (day, hour) => minutes, or null for a slot with no sample
 */
export const lookupGrid = (minutesAt) => ({
  id: "your-lookup",
  name: "SJSU → Palo Alto",
  partial: true,
  distanceMeters: null,
  sampleCount: 5,
  buckets: Object.fromEntries(
    WEEKDAYS.map((day) => [
      day,
      WEEKDAY_HOURS.map((hour) => bucket(hour, minutesAt(day, hour))),
    ])
  ),
  notice: null,
  resetsAt: null,
});
