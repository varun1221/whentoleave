import { describe, expect, it } from "vitest";
import { leaveBy } from "./leaveBy.js";

/** Minutes → seconds, so the fixtures read in the units a human thinks in. */
const b = (slotHour, minutes, n = 6) => ({
  slotHour,
  medianSeconds: minutes == null ? null : minutes * 60,
  n,
});

describe("leaveBy", () => {
  it("picks the latest departure that still arrives on time", () => {
    // Arrive by 09:00. Leaving at 08:00 takes 45m → 08:45, fine.
    // Leaving at 09:00 takes 40m → 09:40, too late.
    const result = leaveBy([b(6, 30), b(7, 40), b(8, 45), b(9, 40)], 9 * 60);
    expect(result.onTime.slotHour).toBe(8);
    expect(result.impossible).toBeNull();
  });

  /**
   * The regression this function exists to avoid: durations are not monotonic, so
   * scanning for the *first* on-time slot hands back a needlessly early alarm.
   */
  it("does not stop at the first slot that happens to work", () => {
    // 06:00 works, 07:00 is a peak and fails, 08:00 works again and is later.
    const result = leaveBy([b(6, 50), b(7, 75), b(8, 55)], 9 * 60 + 10);
    expect(result.onTime.slotHour).toBe(8);
  });

  it("reports the two slots after the safe one with how late each lands", () => {
    const result = leaveBy([b(7, 40), b(8, 45), b(9, 50), b(10, 35)], 9 * 60);
    expect(result.onTime.slotHour).toBe(8);
    expect(result.alternatives.map((a) => a.slotHour)).toEqual([9, 10]);
    expect(result.alternatives[0].minutesLate).toBe(50); // 09:00 + 50m = 09:50
    expect(result.alternatives[1].minutesLate).toBe(95); // 10:00 + 35m = 10:35
  });

  it("offers at most two alternatives even when more slots exist", () => {
    const result = leaveBy([b(6, 20), b(7, 90), b(8, 90), b(9, 90), b(10, 90)], 7 * 60);
    expect(result.alternatives).toHaveLength(2);
  });

  it("offers no alternatives when the safe slot is the last of the day", () => {
    const result = leaveBy([b(17, 30), b(18, 30)], 19 * 60);
    expect(result.onTime.slotHour).toBe(18);
    expect(result.alternatives).toEqual([]);
  });

  it("says so when no departure makes the deadline, and by how little", () => {
    // Earliest possible arrival is 06:00 + 90m = 07:30, deadline is 07:00.
    const result = leaveBy([b(6, 90), b(7, 100)], 7 * 60);
    expect(result.onTime).toBeNull();
    expect(result.impossible.slotHour).toBe(6);
    expect(result.impossible.minutesLate).toBe(30);
  });

  it("ignores unsampled cells rather than treating them as instant", () => {
    const result = leaveBy([b(6, null), b(7, null), b(8, 30)], 9 * 60);
    expect(result.onTime.slotHour).toBe(8);
    expect(result.onTime.durationSeconds).toBe(1800);
  });

  it("returns an empty result for a day with no data at all", () => {
    expect(leaveBy([b(6, null), b(7, null)], 9 * 60)).toEqual({
      onTime: null,
      alternatives: [],
      impossible: null,
    });
    expect(leaveBy([], 540).onTime).toBeNull();
    expect(leaveBy(undefined, 540).onTime).toBeNull();
  });

  it("sorts by hour, so bucket order in the JSON does not matter", () => {
    const result = leaveBy([b(9, 40), b(6, 30), b(8, 45)], 9 * 60);
    expect(result.onTime.slotHour).toBe(8);
  });
});
