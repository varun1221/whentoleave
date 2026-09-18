import { describe, expect, it } from "vitest";
import { coordSuggestion } from "./places.js";

describe("coordSuggestion", () => {
  it("accepts a pasted coordinate pair as a place", () => {
    expect(coordSuggestion("37.3352,-121.8811")).toEqual({
      coord: "37.3352,-121.8811",
      description: "37.3352, -121.8811",
    });
  });

  it("tolerates the space a map paste leaves behind", () => {
    expect(coordSuggestion("  37.3352, -121.8811 ")?.coord).toBe("37.3352,-121.8811");
  });

  it("is not a coordinate when it is an address", () => {
    expect(coordSuggestion("San Jose State University")).toBeNull();
    expect(coordSuggestion("37.3352")).toBeNull();
    expect(coordSuggestion("")).toBeNull();
  });

  /**
   * `LookupRequest.COORD` server side checks the shape, not the range, so a pair like
   * this would be accepted and routed against. Catching it here is the only thing that
   * stops a typo becoming a TomTom call against a place that does not exist.
   */
  it("rejects coordinates that are not on Earth", () => {
    expect(coordSuggestion("91,-121.88")).toBeNull();
    expect(coordSuggestion("37.33,-181")).toBeNull();
  });
});
