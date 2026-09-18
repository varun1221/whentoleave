import { describe, expect, it } from "vitest";
import { noticeFor, resetLabel } from "./notices.js";

/**
 * Pacific midnight on 18 Sep 2026, the reset the service actually sends. The locale is
 * pinned in these tests only to keep the expected strings stable — in the browser the
 * viewer's own locale and zone are used, which is the whole point of formatting here.
 */
const RESET = "2026-09-18T07:00:00Z";
/** Thursday afternoon in California, the hour a commuter would be using this. */
const NOW = Date.parse("2026-09-17T22:00:00Z");
const PACIFIC = { timeZone: "America/Los_Angeles", locale: "en-US", now: NOW };

describe("resetLabel", () => {
  it("reads the reset in the viewer's own zone", () => {
    expect(resetLabel(RESET, PACIFIC)).toBe("12:00 AM Fri");
  });

  /** A visitor in London is told their 08:00, not a Pacific time they'd misread. */
  it("shifts for a viewer in another zone", () => {
    expect(
      resetLabel(RESET, { timeZone: "Europe/London", locale: "en-US", now: NOW })
    ).toBe("8:00 AM Fri");
  });

  /**
   * The reset is Pacific midnight, so for a viewer further east it lands in the small
   * hours of a day they have not started. A bare "3:00 AM" would read as this morning,
   * already gone, so the day comes with it.
   */
  it("names the day when the reset is not today where the viewer is", () => {
    expect(resetLabel(RESET, { timeZone: "America/New_York", locale: "en-US", now: NOW }))
      .toBe("3:00 AM Fri");
  });

  /** And drops the day when it would be noise: for this viewer the reset is today. */
  it("gives the bare time when the reset falls on the viewer's own day", () => {
    expect(resetLabel(RESET, { timeZone: "Asia/Tokyo", locale: "en-US", now: NOW }))
      .toBe("4:00 PM");
  });

  it("has nothing to say without a reset time", () => {
    expect(resetLabel(null, PACIFIC)).toBeNull();
    expect(resetLabel("not a date", PACIFIC)).toBeNull();
  });
});

describe("noticeFor", () => {
  it("tells a rate-limited visitor their limit and when it lifts", () => {
    const notice = noticeFor("rate_limited", {
      dailyLimit: 5,
      resetsAt: RESET,
      ...PACIFIC,
    });

    expect(notice.text).toBe(
      "You’ve used your 5 lookups for today. You get 5 more at 12:00 AM Fri."
    );
    expect(notice.tone).toBe("limit");
  });

  it("falls back to the plain sentence when no reset time came back", () => {
    const notice = noticeFor("rate_limited", { dailyLimit: 5, resetsAt: null });

    expect(notice.text).toBe("You’ve used your 5 lookups for today.");
  });

  it("says the shared budget is spent, not that the visitor did something wrong", () => {
    const notice = noticeFor("quota_exhausted", { resetsAt: RESET, ...PACIFIC });

    expect(notice.text).toBe(
      "Live lookups are paused until tomorrow — the day’s shared budget is spent. " +
        "Cached corridors are still below. Back at 12:00 AM Fri."
    );
  });

  /** The kill switch ends when a human ends it, so it promises no time. */
  it("promises no time for the kill switch", () => {
    const notice = noticeFor("lookups_paused", { resetsAt: null });

    expect(notice.text).toBe(
      "Live lookups are switched off right now. Cached corridors are still below."
    );
  });

  it("explains a forbidden request as a routing problem, not a limit", () => {
    expect(noticeFor("forbidden", {}).tone).toBe("error");
  });

  it("prefers the server's own sentence for a rejected request", () => {
    const notice = noticeFor("invalid_request", {
      message: "origin: Expected \"lat,lon\"",
    });

    expect(notice.text).toBe("origin: Expected \"lat,lon\"");
    expect(notice.tone).toBe("error");
  });

  /** A code this build has never heard of must still render as something. */
  it("has a sentence for an unrecognised code", () => {
    const notice = noticeFor("something_new", {});

    expect(notice.text).toBeTruthy();
    expect(notice.tone).toBe("error");
  });

  it("has nothing to show for a grid with no notice", () => {
    expect(noticeFor(null, {})).toBeNull();
  });
});
