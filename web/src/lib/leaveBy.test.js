import { describe, expect, it } from "vitest";
import { BUFFER_MINUTES, leaveBy } from "./leaveBy.js";

/** Minutes → seconds, so the fixtures read in the units a human thinks in. */
const b = (slotHour, minutes, n = 6) => ({
  slotHour,
  medianSeconds: minutes == null ? null : minutes * 60,
  n,
});

const at = (h, m = 0) => h * 60 + m;

describe("leaveBy", () => {
  it("answers to the minute by reading between adjacent sampled hours", () => {
    // 08:00 → 25m → 08:25 is early; 09:00 → 35m → 09:35 is late. Arrival climbs
    // 70 min over that hour, so the 09:00 deadline is crossed 35/70 of the way: 08:30.
    const { onTime } = leaveBy([b(7, 20), b(8, 25), b(9, 35)], at(9));
    expect(onTime.departMinute).toBe(at(8, 30));
    expect(onTime.durationSeconds).toBe(30 * 60);
    expect(onTime.arrivalMinute).toBe(at(9));
    expect(onTime.lastSampled).toBe(false);
  });

  it("never rounds an answer into being late", () => {
    const { onTime } = leaveBy([b(8, 20), b(9, 50)], at(8, 50));
    expect(onTime.arrivalMinute).toBeLessThanOrEqual(at(8, 50));
  });

  it("lands on a sampled hour when that hour arrives exactly on time", () => {
    const { onTime } = leaveBy([b(8, 60), b(9, 70)], at(9));
    expect(onTime.departMinute).toBe(at(8));
  });

  /**
   * The regression this function exists to avoid: durations are not monotonic, so
   * scanning for the *first* on-time departure hands back a needlessly early alarm.
   */
  it("does not stop at the first window that happens to work", () => {
    // 06:xx works, 07:00 is a peak and fails, 08:00 works again and is later.
    const { onTime } = leaveBy([b(6, 50), b(7, 75), b(8, 55)], at(9, 10));
    expect(onTime.departMinute).toBe(at(8));
  });

  it("never bridges a gap between sampled hours", () => {
    // A lookup sampled 09:00 and 16:00 says nothing about noon.
    const { onTime } = leaveBy([b(9, 30), b(16, 40)], at(14));
    expect(onTime.departMinute).toBe(at(9));
    expect(onTime.lastSampled).toBe(true);
  });

  it("flags an answer pinned to the day's last sample", () => {
    const { onTime } = leaveBy([b(17, 30), b(18, 30)], at(21));
    expect(onTime.departMinute).toBe(at(18));
    expect(onTime.lastSampled).toBe(true);
  });

  it("offers a departure that arrives with time to spare", () => {
    const { onTime, buffer } = leaveBy([b(8, 30), b(9, 30)], at(9, 30));
    expect(onTime.departMinute).toBe(at(9));
    expect(buffer.departMinute).toBe(at(9, -BUFFER_MINUTES));
    expect(buffer.arrivalMinute).toBe(at(9, 30 - BUFFER_MINUTES));
  });

  it("drops the buffer when no departure leaves room for it", () => {
    const { onTime, buffer } = leaveBy([b(8, 55)], at(9));
    expect(onTime.departMinute).toBe(at(8));
    expect(buffer).toBeNull();
  });

  it("says so when no departure makes the deadline, and by how little", () => {
    // Earliest possible arrival is 06:00 + 90m = 07:30, deadline is 07:00.
    const result = leaveBy([b(6, 90), b(7, 100)], at(7));
    expect(result.onTime).toBeNull();
    expect(result.buffer).toBeNull();
    expect(result.impossible.departMinute).toBe(at(6));
    expect(result.impossible.minutesLate).toBe(30);
  });

  it("ignores unsampled cells rather than treating them as instant", () => {
    const { onTime } = leaveBy([b(6, null), b(7, null), b(8, 30)], at(9));
    expect(onTime.departMinute).toBe(at(8));
    expect(onTime.durationSeconds).toBe(1800);
  });

  it("returns an empty result for a day with no data at all", () => {
    expect(leaveBy([b(6, null), b(7, null)], at(9))).toEqual({
      onTime: null,
      buffer: null,
      impossible: null,
    });
    expect(leaveBy([], 540).onTime).toBeNull();
    expect(leaveBy(undefined, 540).onTime).toBeNull();
  });

  it("sorts by hour, so bucket order in the JSON does not matter", () => {
    const { onTime } = leaveBy([b(9, 35), b(7, 20), b(8, 25)], at(9));
    expect(onTime.departMinute).toBe(at(8, 30));
  });
});
