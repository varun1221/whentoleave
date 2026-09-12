import { describe, expect, it } from "vitest";
import {
  RAMP_DARK,
  RAMP_LIGHT,
  colorFor,
  inkFor,
  luminance,
  sampleRamp,
} from "./colorScale.js";

const lstar = (y) => (y > 0.008856 ? 116 * Math.cbrt(y) - 16 : 903.3 * y);

describe("the heat ramps", () => {
  /**
   * The property that makes a green→amber→red scale safe for the ~8% of men with
   * red-green color vision deficiency: lightness alone still encodes the magnitude,
   * so losing hue degrades the scale to a readable grayscale gradient.
   */
  it("fall monotonically in perceptual lightness on light surfaces", () => {
    const steps = RAMP_LIGHT.map((hex) => lstar(luminance(hex)));
    for (let i = 1; i < steps.length; i++) {
      expect(steps[i]).toBeLessThan(steps[i - 1]);
    }
  });

  it("rise monotonically in perceptual lightness on dark surfaces", () => {
    const steps = RAMP_DARK.map((hex) => lstar(luminance(hex)));
    for (let i = 1; i < steps.length; i++) {
      expect(steps[i]).toBeGreaterThan(steps[i - 1]);
    }
  });

  /** Adjacent steps must be far enough apart to survive being read in grayscale. */
  it("keep every adjacent step at least 10 L* apart", () => {
    for (const ramp of [RAMP_LIGHT, RAMP_DARK]) {
      const steps = ramp.map((hex) => lstar(luminance(hex)));
      for (let i = 1; i < steps.length; i++) {
        expect(Math.abs(steps[i] - steps[i - 1])).toBeGreaterThanOrEqual(10);
      }
    }
  });
});

describe("sampleRamp", () => {
  it("returns the endpoints exactly", () => {
    expect(sampleRamp(RAMP_LIGHT, 0)).toBe(RAMP_LIGHT[0]);
    expect(sampleRamp(RAMP_LIGHT, 1)).toBe(RAMP_LIGHT[RAMP_LIGHT.length - 1]);
  });

  it("clamps out-of-range positions instead of extrapolating", () => {
    expect(sampleRamp(RAMP_LIGHT, -5)).toBe(RAMP_LIGHT[0]);
    expect(sampleRamp(RAMP_LIGHT, 5)).toBe(RAMP_LIGHT[RAMP_LIGHT.length - 1]);
  });

  it("interpolates monotonically in lightness across the whole ramp", () => {
    let previous = Infinity;
    for (let t = 0; t <= 1.0001; t += 0.05) {
      const current = lstar(luminance(sampleRamp(RAMP_LIGHT, t)));
      expect(current).toBeLessThan(previous);
      previous = current;
    }
  });
});

describe("colorFor", () => {
  it("scales against the route's own fastest and slowest", () => {
    expect(colorFor(600, 600, 3600, RAMP_LIGHT)).toBe(RAMP_LIGHT[0]);
    expect(colorFor(3600, 600, 3600, RAMP_LIGHT)).toBe(RAMP_LIGHT[4]);
  });

  it("returns null for an unsampled cell so the UI can style it as empty", () => {
    expect(colorFor(null, 600, 3600, RAMP_LIGHT)).toBeNull();
  });

  it("does not divide by zero when every cell is identical", () => {
    expect(colorFor(600, 600, 600, RAMP_LIGHT)).toBe(RAMP_LIGHT[0]);
  });
});

describe("inkFor", () => {
  it("puts dark ink on the pale end and light ink on the deep end", () => {
    expect(inkFor(RAMP_LIGHT[0])).toBe("#1a1a19");
    expect(inkFor(RAMP_LIGHT[4])).toBe("#ffffff");
  });
});
