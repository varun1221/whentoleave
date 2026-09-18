import { useState } from "react";
import PlaceField from "./PlaceField.jsx";
import Banner from "./Banner.jsx";
import { ApiFailure, requestLookup } from "../lib/api.js";
import { noticeFor } from "../lib/notices.js";

/** "1 Hacker Way, Menlo Park, CA" → "1 Hacker Way", for a tab label. */
const shortLabel = (description) => description.split(",")[0].trim();

/**
 * Any corridor, not just the seeded five.
 *
 * Rendered only when `/api/quota` answered — an unreachable backend hides this panel
 * entirely rather than offering a form that cannot work (§10.4). Everything the service
 * refuses arrives as an `ApiFailure` with a code, and every code has a sentence, so a
 * limit is stated rather than swallowed.
 */
export default function LookupPanel({ quota, onResult, onQuotaChange }) {
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
    try {
      const grid = await requestLookup({ origin: origin.coord, dest: dest.coord });
      onResult({
        grid,
        name: `${shortLabel(origin.description)} → ${shortLabel(dest.description)}`,
      });
    } catch (thrown) {
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
    <section className="panel lookup">
      <div className="panel-head">
        <div>
          <h2>Look up your own corridor</h2>
          <p className="subhead">
            Weekday peak hours only — {quota.yourDailyLimit} lookups per visitor a day,
            and a corridor someone already asked about is free. A pasted{" "}
            <code>lat,lon</code> works in place of a search.
          </p>
        </div>
        <QuotaReadout quota={quota} />
      </div>

      <form className="lookup-form" onSubmit={submit}>
        <PlaceField
          label="From"
          value={origin}
          onChange={setOrigin}
          placeholder="San Jose State University"
        />
        <PlaceField
          label="To"
          value={dest}
          onChange={setDest}
          placeholder="Montgomery St, San Francisco"
        />
        {/* Not disabled when the budgets are spent: a corridor that is already cached
            costs nothing and is served anyway, with a notice attached. Refusing here
            would withhold an answer the service would have given. */}
        <button type="submit" className="lookup-submit" disabled={!ready}>
          {busy ? "Looking up…" : "Forecast this"}
        </button>
      </form>

      <Banner notice={notice} />
    </section>
  );
}

/**
 * Both budgets, in the open. §10.4: visible limits read as intentional design, and a
 * visitor who can see four lookups left does not experience the fifth as a failure.
 */
function QuotaReadout({ quota }) {
  return (
    <dl className="quota">
      <div>
        <dt>Your lookups</dt>
        <dd>
          {quota.yourRemaining} / {quota.yourDailyLimit}
        </dd>
      </div>
      <div>
        <dt>Your searches</dt>
        <dd>
          {quota.yourSearchesRemaining} / {quota.yourSearchDailyLimit}
        </dd>
      </div>
      <div>
        <dt>Shared today</dt>
        <dd>
          {quota.globalRemaining} / {quota.globalDailyCeiling}
        </dd>
      </div>
      {quota.lookupsPaused && (
        <div>
          <dt>Live lookups</dt>
          <dd className="quota-paused">paused</dd>
        </div>
      )}
    </dl>
  );
}
