import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import Heatmap from "./Heatmap.jsx";
import { gridStats } from "../lib/grid.js";
import { RAMP_LIGHT, EMPTY_LIGHT } from "../lib/colorScale.js";
import { lookupGrid } from "../lib/testGrids.js";

/**
 * Markup checks for the grid shape Phase 2 introduced: weekdays only, peak hours only,
 * and sometimes nothing sampled at all.
 *
 * `renderToStaticMarkup` ships with react-dom, so this needs no DOM environment and no
 * testing library — enough to catch the crash these shapes actually cause, which is a
 * fixed Monday–Sunday walk reading an undefined row. Behaviour that needs clicks is
 * verified against the running service instead.
 */
const heatmapFor = (grid) => {
  const stats = gridStats(grid);
  return renderToStaticMarkup(
    <Heatmap
      route={grid}
      days={stats.days}
      hours={stats.hours}
      min={stats.min}
      max={stats.max}
      best={stats.best}
      worst={stats.worst}
      ramp={RAMP_LIGHT}
      emptyColor={EMPTY_LIGHT}
      selectedDay="MONDAY"
      onSelectDay={() => {}}
      showNumbers
    />
  );
};

describe("Heatmap with a lookup grid", () => {
  it("renders the five weekdays and no weekend row", () => {
    const html = heatmapFor(lookupGrid(() => 40));

    expect(html).toContain(">Mon<");
    expect(html).toContain(">Fri<");
    expect(html).not.toContain(">Sat<");
    expect(html).not.toContain(">Sun<");
  });

  it("labels the fastest and slowest cell of a partial grid", () => {
    const html = heatmapFor(
      lookupGrid((day, hour) => (hour === 6 ? 30 : hour === 17 ? 80 : 50))
    );

    expect(html).toContain(">best<");
    expect(html).toContain(">worst<");
  });

  /** A cold lookup that filled nothing: every cell empty, still a grid, no crash. */
  it("renders a grid with no samples at all", () => {
    const html = heatmapFor(lookupGrid(() => null));

    expect(html).toContain("no samples");
    expect(html).not.toContain(">best<");
  });
});
