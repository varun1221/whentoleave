import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import LookupPanel from "./LookupPanel.jsx";

/**
 * The panel's job before anyone touches it: state both budgets. §10.4 — "Visible limits
 * read as intentional design; silent failures read as broken."
 */
const QUOTA = {
  yourRemaining: 4,
  yourDailyLimit: 5,
  yourSearchesRemaining: 18,
  yourSearchDailyLimit: 20,
  globalRemaining: 137,
  globalDailyCeiling: 150,
  lookupsPaused: false,
  lookupsAvailable: true,
  resetsAt: "2026-09-18T07:00:00Z",
};

const render = (quota) =>
  renderToStaticMarkup(
    <LookupPanel quota={quota} onResult={() => {}} onQuotaChange={() => {}} />
  );

describe("LookupPanel", () => {
  it("shows every budget, so a limit is visible before it is hit", () => {
    const html = render(QUOTA);

    expect(html).toContain("4 / 5");
    expect(html).toContain("18 / 20");
    expect(html).toContain("137 / 150");
  });

  it("says so when the switch is thrown", () => {
    expect(render({ ...QUOTA, lookupsPaused: true })).toContain("paused");
  });

  /**
   * Not disabled when the budgets are spent: a cached corridor is served anyway, with a
   * notice attached, so refusing here would withhold an answer the service would give.
   */
  it("still offers the form with nothing left", () => {
    const html = render({
      ...QUOTA,
      yourRemaining: 0,
      globalRemaining: 0,
      lookupsAvailable: false,
    });

    expect(html).toContain("Forecast this");
  });
});
