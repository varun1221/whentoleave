/**
 * The heatmap's color scale.
 *
 * Travel duration is a magnitude, so this is a *sequential* encoding. The usual rule
 * for sequential is one hue, light to dark — a multi-hue ramp is normally a
 * "rainbow" mistake, because hue carries no natural order. Congestion is the
 * documented exception: green/amber/red is a semantic-heat convention every driver
 * already reads, and it ships with a scale legend so the mapping is never guessed.
 *
 * Taking that exception costs something: green-to-red is exactly the axis a
 * red-green colorblind reader loses, and that is ~8% of men. So both ramps below are
 * built to be *monotonic in perceptual lightness* (CIE L*), which means when hue
 * fails the scale still reads as a clean light-to-dark gradient. Verified when
 * chosen: minimum step 13.4 L* on light, 13.6 on dark, strictly ordered.
 *
 * Dark mode is not a flip of the light ramp. It is its own set of steps, anchored so
 * the fastest cell recedes toward the dark surface and the slowest one is the
 * brightest thing on screen — the same "worst is loudest" reading, inverted for the
 * background it sits on.
 */

/** Fastest → slowest, on a light surface. */
export const RAMP_LIGHT = ["#e5f4de", "#9ccf84", "#c89434", "#a83f28", "#67140f"];

/** Fastest → slowest, on a dark surface. */
export const RAMP_DARK = ["#1d3a20", "#31612d", "#77802e", "#e08f4e", "#f8b8a4"];

/** Cells with no sample get a flat surface tint, never a ramp color. */
export const EMPTY_LIGHT = "#efeff1";
export const EMPTY_DARK = "#1f1f23";

const hexToRgb = (hex) => [
  parseInt(hex.slice(1, 3), 16),
  parseInt(hex.slice(3, 5), 16),
  parseInt(hex.slice(5, 7), 16),
];

const rgbToHex = (rgb) =>
  "#" + rgb.map((c) => Math.round(c).toString(16).padStart(2, "0")).join("");

/** Position `t` (0..1) along a ramp, interpolating between its anchors. */
export function sampleRamp(ramp, t) {
  const clamped = Math.min(1, Math.max(0, t));
  if (!Number.isFinite(clamped)) return ramp[0];
  const scaled = clamped * (ramp.length - 1);
  const i = Math.min(ramp.length - 2, Math.floor(scaled));
  const frac = scaled - i;
  const a = hexToRgb(ramp[i]);
  const b = hexToRgb(ramp[i + 1]);
  return rgbToHex(a.map((c, k) => c + (b[k] - c) * frac));
}

/**
 * Color for one cell, scaled between this route's own fastest and slowest median.
 * Per-route rather than global: a 17-minute corridor and a 96-minute one each need
 * their own full range, or the short one collapses to a single shade.
 */
export function colorFor(seconds, min, max, ramp) {
  if (seconds == null) return null;
  if (max === min) return sampleRamp(ramp, 0);
  return sampleRamp(ramp, (seconds - min) / (max - min));
}

/** Relative luminance, for picking readable ink on top of a cell. */
export function luminance(hex) {
  const [r, g, b] = hexToRgb(hex).map((c) => {
    const s = c / 255;
    return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/** Ink that stays legible on a given cell color. */
export function inkFor(hex) {
  return luminance(hex) > 0.42 ? "#1a1a19" : "#ffffff";
}
