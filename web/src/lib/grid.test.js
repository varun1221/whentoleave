import { describe, expect, it } from "vitest";
import { dayWithin, fillProgress, gridStats, spreadPercent } from "./grid.js";

const bucket = (slotHour, minutes, n = 3) => ({
  slotHour,
  medianSeconds: minutes == null ? null : minutes * 60,
  n,
});

const ALL_DAYS = [
  "MONDAY",
  "TUESDAY",
  "WEDNESDAY",
  "THURSDAY",
  "FRIDAY",
  "SATURDAY",
  "SUNDAY",
];

/** A grid with the same row on every day given. */
const gridOf = (days, row) => ({
  buckets: Object.fromEntries(days.map((day) => [day, row])),
});

describe("gridStats", () => {
  it("spans the whole week for a seeded corridor", () => {
    const stats = gridStats(gridOf(ALL_DAYS, [bucket(6, 40), bucket(7, 60)]));

    expect(stats.days).toEqual(ALL_DAYS);
    expect(stats.hours).toEqual([6, 7]);
    expect(stats.min).toBe(40 * 60);
    expect(stats.max).toBe(60 * 60);
  });

  /**
   * The reason this is a function and not inline in App: a lookup grid is weekdays
   * only, and walking a fixed Monday–Sunday list over it reads undefined buckets.
   */
  it("covers only the days a reduced lookup grid actually has", () => {
    const weekdays = ALL_DAYS.slice(0, 5);
    const stats = gridStats(gridOf(weekdays, [bucket(6, 30), bucket(17, 55)]));

    expect(stats.days).toEqual(weekdays);
    expect(stats.hours).toEqual([6, 17]);
  });

  it("keeps the days in week order however the response orders them", () => {
    const stats = gridStats({
      buckets: {
        WEDNESDAY: [bucket(6, 30)],
        MONDAY: [bucket(6, 30)],
        FRIDAY: [bucket(6, 30)],
      },
    });

    expect(stats.days).toEqual(["MONDAY", "WEDNESDAY", "FRIDAY"]);
  });

  it("marks the fastest and slowest cell across every day", () => {
    const stats = gridStats({
      buckets: {
        MONDAY: [bucket(6, 40), bucket(7, 90)],
        TUESDAY: [bucket(6, 25), bucket(7, 50)],
      },
    });

    expect(stats.best).toEqual({ day: "TUESDAY", slotHour: 6 });
    expect(stats.worst).toEqual({ day: "MONDAY", slotHour: 7 });
  });

  /** A cold lookup that filled nothing still has to render rather than throw. */
  it("reports an all-empty grid as empty instead of a zero-minute trip", () => {
    const stats = gridStats(gridOf(["MONDAY"], [bucket(6, null, 0), bucket(7, null, 0)]));

    expect(stats.empty).toBe(true);
    expect(stats.best).toBeNull();
    expect(stats.worst).toBeNull();
    expect(stats.hours).toEqual([6, 7]);
  });

  it("is empty for a grid with no days at all", () => {
    const stats = gridStats({ buckets: {} });

    expect(stats.empty).toBe(true);
    expect(stats.days).toEqual([]);
    expect(stats.hours).toEqual([]);
  });

  it("counts a single sampled cell as both fastest and slowest", () => {
    const stats = gridStats(gridOf(["MONDAY"], [bucket(6, 42), bucket(7, null, 0)]));

    expect(stats.empty).toBe(false);
    expect(stats.min).toBe(42 * 60);
    expect(stats.max).toBe(42 * 60);
    expect(stats.best).toEqual({ day: "MONDAY", slotHour: 6 });
  });

  it("picks a day that exists to read the hours from", () => {
    const stats = gridStats({
      buckets: { SATURDAY: [bucket(9, 20), bucket(10, 22)] },
    });

    expect(stats.hours).toEqual([9, 10]);
  });
});

describe("spreadPercent", () => {
  it("measures the swing the whole app exists to show", () => {
    expect(spreadPercent(gridStats(gridOf(["MONDAY"], [bucket(6, 50), bucket(7, 75)]))))
      .toBe(50);
  });

  it("is zero when there is nothing to compare", () => {
    expect(spreadPercent(gridStats({ buckets: {} }))).toBe(0);
  });
});

describe("dayWithin", () => {
  it("keeps the selected day when the grid has it", () => {
    const stats = gridStats(gridOf(ALL_DAYS, [bucket(6, 30)]));

    expect(dayWithin(stats, "SATURDAY")).toBe("SATURDAY");
  });

  /** Switching to a weekday-only lookup with Saturday selected must not read undefined. */
  it("falls back to the first day a reduced grid has", () => {
    const stats = gridStats(gridOf(ALL_DAYS.slice(0, 5), [bucket(6, 30)]));

    expect(dayWithin(stats, "SATURDAY")).toBe("MONDAY");
  });

  it("has no day to offer for an empty grid", () => {
    expect(dayWithin(gridStats({ buckets: {} }), "MONDAY")).toBeNull();
  });
});

describe("fillProgress", () => {
  it("counts the hours that have a sample, across every day, out of all of them", () => {
    const bucket = (slotHour, n) => ({ slotHour, medianSeconds: n ? 1800 : null, n });

    expect(
      fillProgress({
        buckets: {
          MONDAY: [bucket(6, 1), bucket(7, 0)],
          TUESDAY: [bucket(6, 2), bucket(7, 1)],
        },
      })
    ).toEqual({ filled: 3, total: 4 });
  });
});
