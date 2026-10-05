import { describe, expect, it } from "vitest";
import { ApiFailure, getQuota, joinUrl, requestLookup, suggestPlaces } from "./api.js";
import { eventStreamResponse, sseEvent } from "./testStreams.js";

/** A fetch that answers once with the given status and body. */
const answers = (status, body) => () =>
  Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(body),
  });

const refuses = (error) => () => Promise.reject(error);

const GRID = { id: null, buckets: { MONDAY: [] }, notice: null, resetsAt: null };

describe("joinUrl", () => {
  it("leaves a same-origin path alone", () => {
    expect(joinUrl("", "/api/quota")).toBe("/api/quota");
  });

  it("puts the API on its own host when one is configured", () => {
    expect(joinUrl("https://api.example.com", "/api/quota")).toBe(
      "https://api.example.com/api/quota"
    );
  });

  it("tolerates a trailing slash on the configured base", () => {
    expect(joinUrl("https://api.example.com/", "/api/quota")).toBe(
      "https://api.example.com/api/quota"
    );
  });
});

describe("getQuota", () => {
  it("returns the body of a healthy response", async () => {
    const quota = { yourRemaining: 4, yourDailyLimit: 5, lookupsAvailable: true };

    await expect(getQuota({ fetchImpl: answers(200, quota) })).resolves.toEqual(quota);
  });

  /**
   * §10.4: backend unreachable is a normal state. It has to arrive as a code the caller
   * can branch on — that branch is what hides the panel instead of logging noise.
   */
  it("turns an unreachable service into an unreachable failure", async () => {
    const failure = await getQuota({
      fetchImpl: refuses(new TypeError("Failed to fetch")),
    }).catch((e) => e);

    expect(failure).toBeInstanceOf(ApiFailure);
    expect(failure.code).toBe("unreachable");
  });

  it("treats a 5xx as unreachable too — there is no quota to show either way", async () => {
    const failure = await getQuota({ fetchImpl: answers(503, "") }).catch((e) => e);

    expect(failure.code).toBe("unreachable");
    expect(failure.status).toBe(503);
  });
});

describe("requestLookup", () => {
  it("posts the two coordinate strings and returns the grid", async () => {
    let seen = null;
    const fetchImpl = (url, options) => {
      seen = { url, options };
      return answers(200, GRID)();
    };

    const grid = await requestLookup(
      { origin: "37.3,-121.8", dest: "37.7,-122.4" },
      { fetchImpl }
    );

    expect(grid).toEqual(GRID);
    expect(seen.url).toBe("/api/lookup");
    expect(seen.options.method).toBe("POST");
    expect(JSON.parse(seen.options.body)).toEqual({
      origin: "37.3,-121.8",
      dest: "37.7,-122.4",
    });
  });

  it("carries a 429's code, limit and reset time onto the failure", async () => {
    const body = {
      error: "rate_limited",
      message: "You have used your 5 lookups for today",
      dailyLimit: 5,
      resetsAt: "2026-09-18T07:00:00Z",
    };

    const failure = await requestLookup(
      { origin: "37.3,-121.8", dest: "37.7,-122.4" },
      { fetchImpl: answers(429, body) }
    ).catch((e) => e);

    expect(failure.code).toBe("rate_limited");
    expect(failure.dailyLimit).toBe(5);
    expect(failure.resetsAt).toBe("2026-09-18T07:00:00Z");
    expect(failure.message).toBe("You have used your 5 lookups for today");
  });

  it("carries the code of a rejected request", async () => {
    const failure = await requestLookup(
      { origin: "nowhere", dest: "37.7,-122.4" },
      { fetchImpl: answers(400, { error: "invalid_request", message: "origin: bad" }) }
    ).catch((e) => e);

    expect(failure.code).toBe("invalid_request");
    expect(failure.message).toBe("origin: bad");
  });

  it("survives an error response that is not JSON", async () => {
    const fetchImpl = () =>
      Promise.resolve({
        ok: false,
        status: 502,
        json: () => Promise.reject(new SyntaxError("Unexpected token <")),
      });

    const failure = await requestLookup(
      { origin: "37.3,-121.8", dest: "37.7,-122.4" },
      { fetchImpl }
    ).catch((e) => e);

    expect(failure.code).toBe("unreachable");
  });
});

describe("suggestPlaces", () => {
  it("escapes the query rather than pasting it into the URL", async () => {
    let seen = null;
    const fetchImpl = (url) => {
      seen = url;
      return answers(200, [])();
    };

    await suggestPlaces("palo alto & menlo", { fetchImpl });

    expect(seen).toBe("/api/places/autocomplete?q=palo+alto+%26+menlo");
  });

  /**
   * An autocomplete that fails never throws — the visitor is mid-keystroke — but the
   * reason comes back with the empty list, so a spent search budget can be stated rather
   * than read as a corridor nothing matches.
   */
  it("reports a spent search budget instead of an unexplained empty list", async () => {
    const result = await suggestPlaces("palo alto", {
      fetchImpl: answers(429, { error: "rate_limited" }),
    });

    expect(result).toEqual({ suggestions: [], code: "rate_limited" });
  });

  it("reports an unreachable search the same way", async () => {
    const result = await suggestPlaces("palo alto", {
      fetchImpl: refuses(new TypeError("nope")),
    });

    expect(result).toEqual({ suggestions: [], code: "unreachable" });
  });

  it("does not call out at all below the server's minimum query length", async () => {
    let called = false;
    const fetchImpl = () => {
      called = true;
      return answers(200, [])();
    };

    await expect(suggestPlaces("sj", { fetchImpl })).resolves.toEqual({
      suggestions: [],
      code: null,
    });
    expect(called).toBe(false);
  });

  it("passes an abort signal through so a stale keystroke is dropped", async () => {
    const controller = new AbortController();
    let seen = null;
    const fetchImpl = (_url, options) => {
      seen = options;
      return answers(200, [])();
    };

    await suggestPlaces("palo alto", { fetchImpl, signal: controller.signal });

    expect(seen.signal).toBe(controller.signal);
  });

  it("returns what the search found", async () => {
    const found = [{ coord: "37.44,-122.14", lat: 37.44, lon: -122.14, description: "PA" }];

    await expect(suggestPlaces("palo alto", { fetchImpl: answers(200, found) }))
      .resolves.toEqual({ suggestions: found, code: null });
  });
});

/** A fetch that answers with a server-sent event stream, delivered in these chunks. */
const streams = (...chunks) => () => Promise.resolve(eventStreamResponse(...chunks));

describe("requestLookup, streamed", () => {
  it("asks for the grid as a stream", async () => {
    let sent;
    await requestLookup(
      { origin: "37.1,-122.1", dest: "38.1,-121.1" },
      {
        fetchImpl: (url, options) => {
          sent = options;
          return answers(200, GRID)();
        },
      }
    );

    expect(sent.headers.Accept).toContain("text/event-stream");
  });

  it("reports each grid as it fills and resolves with the finished one", async () => {
    const first = { ...GRID, sampleCount: 0 };
    const second = { ...GRID, sampleCount: 1 };
    const finished = { ...GRID, sampleCount: 2 };
    const wire = sseEvent("progress", first) + sseEvent("progress", second) + sseEvent("done", finished);
    const progress = [];

    const grid = await requestLookup(
      { origin: "37.1,-122.1", dest: "38.1,-121.1" },
      {
        // Split mid-event, as a network will: an event is only whole at its blank line.
        fetchImpl: streams(wire.slice(0, 25), wire.slice(25, 90), wire.slice(90)),
        onProgress: (soFar) => progress.push(soFar),
      }
    );

    expect(progress).toEqual([first, second]);
    expect(grid).toEqual(finished);
  });

  it("treats a stream that ends without its finished grid as unreachable", async () => {
    const progress = [];
    const failure = await requestLookup(
      { origin: "37.1,-122.1", dest: "38.1,-121.1" },
      {
        fetchImpl: streams(sseEvent("progress", GRID)),
        onProgress: (soFar) => progress.push(soFar),
      }
    ).catch((e) => e);

    expect(progress).toEqual([GRID]);
    expect(failure).toBeInstanceOf(ApiFailure);
    expect(failure.code).toBe("unreachable");
  });

  it("answers a cached corridor from plain JSON with no progress", async () => {
    const progress = [];

    const grid = await requestLookup(
      { origin: "37.1,-122.1", dest: "38.1,-121.1" },
      { fetchImpl: answers(200, GRID), onProgress: (soFar) => progress.push(soFar) }
    );

    expect(grid).toEqual(GRID);
    expect(progress).toEqual([]);
  });
});
