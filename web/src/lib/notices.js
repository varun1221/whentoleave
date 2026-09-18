/**
 * The sentence the UI shows for each reason the API gives.
 *
 * One map, keyed on the `error` / `notice` codes the service sends, because a limit
 * arrives two ways for the same cause: as `error` on a 429 when nothing was cached, and
 * as `notice` on a 200 when part of the corridor was. A visitor should be told the same
 * thing either way — how much of their corridor happened to be in the cache is not
 * something they know about.
 *
 * `tone` picks the banner styling: a limit is a normal state and reads as one, an error is
 * something that went wrong. Nothing here is hidden, per §10.4 — a silent failure reads
 * as broken, a stated limit reads as intentional.
 */

/**
 * The reset time in the viewer's own zone, or null when there is no scheduled end.
 *
 * Both budgets refill at Pacific midnight, which for a viewer further east is a small
 * hour of a day that has not started yet — "3:00 AM" alone would read as this morning,
 * already past. So the weekday comes along whenever the reset falls on a different day
 * than the viewer is having.
 */
export function resetLabel(iso, { timeZone, locale, now = Date.now() } = {}) {
  if (!iso) return null;
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return null;

  const time = new Intl.DateTimeFormat(locale, {
    hour: "numeric",
    minute: "2-digit",
    timeZone,
  }).format(at);

  const dayOf = new Intl.DateTimeFormat("en-CA", { dateStyle: "short", timeZone });
  if (dayOf.format(at) === dayOf.format(new Date(now))) return time;

  const weekday = new Intl.DateTimeFormat(locale, { weekday: "short", timeZone })
    .format(at);
  return `${time} ${weekday}`;
}

export function noticeFor(code, options = {}) {
  if (!code) return null;
  const { message, dailyLimit } = options;
  const at = resetLabel(options.resetsAt, options);

  switch (code) {
    case "rate_limited": {
      const limit = dailyLimit ?? 5;
      const head = `You’ve used your ${limit} lookups for today.`;
      return {
        tone: "limit",
        text: at ? `${head} You get ${limit} more at ${at}.` : head,
      };
    }

    case "quota_exhausted": {
      const head =
        "Live lookups are paused until tomorrow — the day’s shared budget is spent. " +
        "Cached corridors are still below.";
      return { tone: "limit", text: at ? `${head} Back at ${at}.` : head };
    }

    // The kill switch has no scheduled end, so this one never names a time.
    case "lookups_paused":
      return {
        tone: "limit",
        text:
          "Live lookups are switched off right now. Cached corridors are still below.",
      };

    // The edge did not add its header, so the service cannot trust the client IP. A
    // deploy problem rather than anything the visitor can act on, said plainly.
    case "forbidden":
      return {
        tone: "error",
        text:
          "Live lookups aren’t available on this address. The seeded corridors below " +
          "are unaffected.",
      };

    case "invalid_request":
      return {
        tone: "error",
        text: message || "That doesn’t look like a place we can route between.",
      };

    default:
      return {
        tone: "error",
        text:
          message ||
          "Live lookups aren’t working right now. The seeded corridors below are " +
            "unaffected.",
      };
  }
}
