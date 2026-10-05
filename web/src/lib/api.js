/**
 * The browser's side of the Phase 2 API.
 *
 * Every call funnels through `request`, which turns an HTTP answer into either a body or
 * an `ApiFailure` carrying the service's own `error` code. The codes are the contract
 * (`ApiCode` server side), so the UI branches on them rather than on status numbers and
 * message text.
 *
 * `fetchImpl` is injectable for the tests: these are the functions where a wrong turn
 * costs a request against a visitor's daily five, so they are worth testing without a
 * network.
 */

/** Server-side `forecast.search.min-query-length`. Autocomplete fires per keystroke. */
const MIN_QUERY_LENGTH = 3;

/**
 * Where the API lives. Empty in dev, where Vite proxies `/api` to localhost:8080, and
 * empty on a Pages deploy until the API hostname is set at build time — in which case
 * every call fails as unreachable and the lookup panel hides itself, which is the
 * correct behaviour for a site whose backend is not up yet.
 */
export const API_BASE = (import.meta.env?.VITE_API_BASE ?? "").trim();

export function joinUrl(base, path) {
  return base.replace(/\/+$/, "") + path;
}

const url = (path) => joinUrl(API_BASE, path);

/** A refusal, an outage, or a rejected request — with the code that tells them apart. */
export class ApiFailure extends Error {
  constructor(code, message, { status, dailyLimit, remaining, resetsAt } = {}) {
    super(message);
    this.name = "ApiFailure";
    this.code = code;
    this.status = status ?? null;
    this.dailyLimit = dailyLimit ?? null;
    this.remaining = remaining ?? null;
    this.resetsAt = resetsAt ?? null;
  }
}

/** Nothing reached the service, or what came back was not the service. */
const unreachable = (status, message) =>
  new ApiFailure("unreachable", message ?? "Service unreachable", { status });

async function send(path, options, fetchImpl) {
  try {
    return await fetchImpl(url(path), options);
  } catch (e) {
    // A network error, a CORS refusal, or an abort. No console noise: §10.4 counts an
    // unreachable backend as a normal state on a site whose Phase 1 half works offline.
    throw unreachable(null, e?.message);
  }
}

async function request(path, options, fetchImpl) {
  return readJson(await send(path, options, fetchImpl));
}

async function readJson(response) {
  let body = null;
  try {
    body = await response.json();
  } catch {
    body = null;
  }

  if (response.ok) return body;

  // A 4xx from the service carries an ApiError body; anything else (a 5xx, a proxy's
  // HTML error page) is indistinguishable from the service being down.
  if (body?.error) {
    throw new ApiFailure(body.error, body.message ?? body.error, {
      status: response.status,
      dailyLimit: body.dailyLimit,
      remaining: body.remaining,
      resetsAt: body.resetsAt,
    });
  }
  throw unreachable(response.status);
}

/** Both budgets and the kill switch. The first call also tells us the API is there. */
export function getQuota({ fetchImpl = fetch, signal } = {}) {
  return request("/api/quota", { signal }, fetchImpl);
}

const EVENT_STREAM = "text/event-stream";

/** Event names, as `GridEvents` sends them. */
const PROGRESS = "progress";
const DONE = "done";

/**
 * A forecast for an arbitrary corridor. Throws `ApiFailure` for every refusal.
 *
 * A corridor nobody has asked about takes a call per hour to fill, about eleven seconds
 * in all, so the service streams it: `onProgress` gets the grid each time another hour is
 * in, and the promise resolves with the finished one. Anything it can answer at once —
 * a cached corridor, a refusal — comes back as one JSON body, with no progress at all.
 */
export async function requestLookup(
  { origin, dest },
  { fetchImpl = fetch, signal, onProgress } = {}
) {
  const response = await send(
    "/api/lookup",
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Accept: `${EVENT_STREAM}, application/json`,
      },
      body: JSON.stringify({ origin, dest }),
      signal,
    },
    fetchImpl
  );
  const type = response.headers?.get?.("Content-Type") ?? "";
  if (!response.ok || !type.includes(EVENT_STREAM)) return readJson(response);

  let finished = null;
  try {
    await readEvents(response.body, (event, data) => {
      if (event === PROGRESS) onProgress?.(JSON.parse(data));
      if (event === DONE) finished = JSON.parse(data);
    });
  } catch (e) {
    throw unreachable(response.status, e?.message);
  }
  // Ending without `done` is the service failing partway. What arrived stays shown.
  if (!finished) throw unreachable(response.status, "The lookup stopped partway");
  return finished;
}

/** Server-sent events off a fetch body: `event:` and `data:` lines, blank-line ended. */
async function readEvents(body, onEvent) {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  for (;;) {
    const { value, done } = await reader.read();
    if (done) return;
    buffer += decoder.decode(value, { stream: true });
    let end;
    while ((end = buffer.indexOf("\n\n")) >= 0) {
      const block = buffer.slice(0, end);
      buffer = buffer.slice(end + 2);
      let event = "message";
      let data = "";
      for (const line of block.split("\n")) {
        if (line.startsWith("event: ")) event = line.slice(7);
        else if (line.startsWith("data: ")) data += line.slice(6);
      }
      onEvent(event, data);
    }
  }
}

/**
 * Address suggestions, and the reason there are none when there are none.
 *
 * A failed search never throws — the visitor is mid-keystroke and has asked for nothing
 * yet, so it must not interrupt them. But it does not vanish either: the code comes back
 * alongside the (empty) list, so a spent search budget can say so instead of looking
 * like a corridor nothing matches. The length floor is the client half of the server's —
 * without it every single letter typed is a billable request.
 */
export async function suggestPlaces(query, { fetchImpl = fetch, signal } = {}) {
  const q = query.trim();
  if (q.length < MIN_QUERY_LENGTH) return { suggestions: [], code: null };
  try {
    const found = await request(
      `/api/places/autocomplete?q=${encodeURIComponent(q).replace(/%20/g, "+")}`,
      { signal },
      fetchImpl
    );
    return {
      suggestions: Array.isArray(found) ? found : [],
      code: null,
    };
  } catch (failure) {
    return { suggestions: [], code: failure.code };
  }
}
