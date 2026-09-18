/** `lat,lon`, matching the pattern `LookupRequest` validates against. */
const COORD = /^(-?\d{1,3}(?:\.\d+)?),\s*(-?\d{1,3}(?:\.\d+)?)$/;

/**
 * A typed coordinate pair, as a suggestion.
 *
 * `/api/lookup` takes coordinates, so a visitor who already has them — off a map, out of
 * the seeded config — should not have to search for the place they are holding. It also
 * costs no address search, which is a separate daily budget, and it is the only way to
 * drive the panel on a local service with no TomTom key configured.
 */
export function coordSuggestion(text) {
  const match = COORD.exec(text.trim());
  if (!match) return null;
  const lat = Number(match[1]);
  const lon = Number(match[2]);
  if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
  return { coord: `${match[1]},${match[2]}`, description: `${match[1]}, ${match[2]}` };
}
