/** Shared display helpers. No date library — these are all integer arithmetic. */

export const DAYS = [
  "MONDAY",
  "TUESDAY",
  "WEDNESDAY",
  "THURSDAY",
  "FRIDAY",
  "SATURDAY",
  "SUNDAY",
];

export const DAY_LABEL = {
  MONDAY: "Mon",
  TUESDAY: "Tue",
  WEDNESDAY: "Wed",
  THURSDAY: "Thu",
  FRIDAY: "Fri",
  SATURDAY: "Sat",
  SUNDAY: "Sun",
};

/** 8 → "08:00" */
export const hourLabel = (hour) => String(hour).padStart(2, "0") + ":00";

/** Minutes past midnight → "08:35" */
export function clockLabel(minute) {
  const wrapped = ((Math.round(minute) % 1440) + 1440) % 1440;
  const h = Math.floor(wrapped / 60);
  const m = wrapped % 60;
  return String(h).padStart(2, "0") + ":" + String(m).padStart(2, "0");
}

/** Seconds → whole minutes, the unit the whole UI speaks in. */
export const toMinutes = (seconds) => Math.round(seconds / 60);

export function formatDate(iso) {
  if (!iso) return "unknown";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "unknown";
  return d.toISOString().slice(0, 10);
}
