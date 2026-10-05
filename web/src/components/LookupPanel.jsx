import { useState } from "react";
import PlaceField from "./PlaceField.jsx";
import Banner from "./Banner.jsx";
import { ApiFailure, requestLookup } from "../lib/api.js";
import { noticeFor } from "../lib/notices.js";

/**
 * A tab label from a suggestion: "1 Hacker Way, Menlo Park, CA" → "1 Hacker Way", and
 * a point of interest ("SJSU — 150 E San Fernando St, …") keeps only its name.
 */
export const shortLabel = (description) =>
  description.split(" — ")[0].split(",")[0].trim();

/**
 * Any corridor, not just the seeded five.
 *
 * Rendered only when `/api/quota` answered — an unreachable backend hides this panel
 * entirely rather than offering a form that cannot work (§10.4). Everything the service
 * refuses arrives as an `ApiFailure` with a code, and every code has a sentence, so a
 * limit is stated rather than swallowed.
 *
 * A corridor that has to be fetched arrives in pieces: `onResult` with the first, as
 * soon as it exists, then `onUpdate` with each grid after it. `filling` is set on all
 * but the last; `stoppedPartway` on the last when the service failed before finishing.
 */
export default function LookupPanel({ quota, onResult, onUpdate, onQuotaChange }) {
  const [origin, setOrigin] = useState(null);
  const [dest, setDest] = useState(null);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState(null);

  const ready = origin != null && dest != null && !busy;

  async function submit(event) {
    event.preventDefault();
    if (!ready) return;
    setBusy(true);
    setFailure(null);
    const corridor = {
      // The same two ends looked up twice are one corridor, and one tab.
      key: `${origin.coord}|${dest.coord}`,
      name: `${shortLabel(origin.description)} → ${shortLabel(dest.description)}`,
    };
    let delivered = null;
    const deliver = (result) => {
      (delivered ? onUpdate : onResult)(result);
      delivered = result;
    };
    try {
      const grid = await requestLookup(
        { origin: origin.coord, dest: dest.coord },
        { onProgress: (soFar) => deliver({ grid: soFar, ...corridor, filling: true }) }
      );
      deliver({ grid, ...corridor });
    } catch (thrown) {
      // Stopped partway: what arrived stays, labelled as short, and nothing more is
      // on its way.
      if (delivered?.filling) {
        deliver({ ...delivered, filling: false, stoppedPartway: true });
      }
      setFailure(
        thrown instanceof ApiFailure
          ? thrown
          : new ApiFailure("unreachable", thrown.message)
      );
    } finally {
      setBusy(false);
      // Whatever happened, the counters moved or the limit is worth re-reading.
      onQuotaChange();
    }
  }

  const notice = failure
    ? noticeFor(failure.code, {
        message: failure.message,
        dailyLimit: failure.dailyLimit ?? quota.yourDailyLimit,
        resetsAt: failure.resetsAt ?? quota.resetsAt,
      })
    : null;

  return (
    <div className="search">
      <form className="search-bar" onSubmit={submit}>
        <PlaceField
          label="From"
          value={origin}
          onChange={setOrigin}
          placeholder="San Jose State University"
        />
        <span className="search-arrow" aria-hidden="true">
          →
        </span>
        <PlaceField
          label="To"
          value={dest}
          onChange={setDest}
          placeholder="Montgomery St, San Francisco"
        />
        {/* Not disabled when the budgets are spent: a corridor that is already cached
            costs nothing and is served anyway, with a notice attached. Refusing here
            would withhold an answer the service would have given. */}
        <button type="submit" className="search-submit" disabled={!ready}>
          {busy ? "Looking up…" : "Forecast this"}
        </button>
      </form>

      {/* The one budget state worth stating up front; every other limit explains
          itself in the banner when it is actually hit. */}
      {quota.lookupsPaused && (
        <p className="search-note quota-paused">Live lookups are paused for now.</p>
      )}
      {!quota.lookupsPaused && !notice && (
        <p className="search-note">
          Search any address or place, or paste a <code>lat,lon</code>
        </p>
      )}
      <Banner notice={notice} />
    </div>
  );
}
