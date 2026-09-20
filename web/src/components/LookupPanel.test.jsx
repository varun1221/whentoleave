import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import LookupPanel from "./LookupPanel.jsx";

/**
 * The panel's job before anyone touches it: state both budgets. §10.4 — "Visible limits
 * read as intentional design; silent failures read as broken."
 */
const QUOTA = {
  yourRemaining: 4,
  yourDailyLimit: 5,
  yourSearchesRemaining: 18,
  yourSearchDailyLimit: 20,
  globalRemaining: 137,
  globalDailyCeiling: 150,
  lookupsPaused: false,
  lookupsAvailable: true,
  resetsAt: "2026-09-18T07:00:00Z",
};

const SUGGESTIONS = [
  { coord: "37.33531,-121.88103", description: "San Jose State University, San Jose, CA" },
  { coord: "37.79430,-122.39560", description: "Montgomery St, San Francisco, CA" },
];

/** What `request` in lib/api.js actually reads off a response: three members. */
const answer = (status, body) => ({
  ok: status < 400,
  status,
  json: async () => body,
});

/**
 * One stub for both endpoints the panel reaches, routed by path.
 *
 * The panel calls `requestLookup` without injecting a `fetchImpl`, exactly as the app
 * does, so the seam under test is the global — anything less would test a wiring the
 * browser never runs.
 */
function stubFetch({ suggestions = SUGGESTIONS, lookup = answer(200, { ok: true }) } = {}) {
  const fetchImpl = vi.fn(async (url) => {
    if (String(url).includes("/api/places/autocomplete")) return answer(200, suggestions);
    if (String(url).includes("/api/lookup")) {
      return typeof lookup === "function" ? lookup() : lookup;
    }
    throw new Error(`unexpected request to ${url}`);
  });
  vi.stubGlobal("fetch", fetchImpl);
  return fetchImpl;
}

function mount(quota = {}) {
  const onResult = vi.fn();
  const onQuotaChange = vi.fn();
  render(
    <LookupPanel
      quota={{ ...QUOTA, ...quota }}
      onResult={onResult}
      onQuotaChange={onQuotaChange}
    />
  );
  return { user: userEvent.setup(), onResult, onQuotaChange };
}

/** Type into one end of the form and take the suggestion, the way a visitor does. */
async function pick(user, label, query, description) {
  await user.type(screen.getByLabelText(label), query);
  await user.click(await screen.findByRole("option", { name: description }));
}

const submit = () => screen.getByRole("button", { name: /forecast this|looking up/i });

const bothEnds = async (user) => {
  await pick(user, "From", "San Jose State", SUGGESTIONS[0].description);
  await pick(user, "To", "Montgomery", SUGGESTIONS[1].description);
};

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("LookupPanel", () => {
  it("shows every budget, so a limit is visible before it is hit", () => {
    mount();

    expect(screen.getByText("4 / 5")).toBeTruthy();
    expect(screen.getByText("18 / 20")).toBeTruthy();
    expect(screen.getByText("137 / 150")).toBeTruthy();
  });

  it("says so when the switch is thrown", () => {
    mount({ lookupsPaused: true });

    expect(screen.getByText("paused")).toBeTruthy();
  });

  /**
   * Not disabled when the budgets are spent: a cached corridor is served anyway, with a
   * notice attached, so refusing here would withhold an answer the service would give.
   */
  it("still offers the form with nothing left", () => {
    mount({ yourRemaining: 0, globalRemaining: 0, lookupsAvailable: false });

    expect(submit()).toBeTruthy();
  });

  it("posts the coordinates behind the two chosen places", async () => {
    const fetchImpl = stubFetch();
    const { user, onResult } = mount();

    await bothEnds(user);
    await user.click(submit());

    const [, options] = fetchImpl.mock.calls.find(([url]) =>
      String(url).includes("/api/lookup")
    );
    expect(options.method).toBe("POST");
    expect(JSON.parse(options.body)).toEqual({
      origin: SUGGESTIONS[0].coord,
      dest: SUGGESTIONS[1].coord,
    });

    // The corridor is named from the two descriptions, first segment each, because the
    // full postal strings do not fit a tab label.
    expect(onResult).toHaveBeenCalledWith({
      grid: { ok: true },
      name: "San Jose State University → Montgomery St",
    });
  });

  /**
   * A pasted `lat,lon` resolves in the browser, never at the service: it costs an address
   * search the visitor has a separate daily budget for, to be told what they just typed.
   */
  it("takes a pasted lat,lon without spending a search", async () => {
    const fetchImpl = stubFetch();
    const { user } = mount();

    await pick(user, "From", "37.33531,-121.88103", "37.33531, -121.88103");

    expect(
      fetchImpl.mock.calls.some(([url]) => String(url).includes("/autocomplete"))
    ).toBe(false);
  });

  it("will not submit until both ends are chosen", async () => {
    stubFetch();
    const { user } = mount();

    expect(submit().disabled).toBe(true);

    await pick(user, "From", "San Jose State", SUGGESTIONS[0].description);
    expect(submit().disabled).toBe(true);

    await pick(user, "To", "Montgomery", SUGGESTIONS[1].description);
    expect(submit().disabled).toBe(false);
  });

  /**
   * Typing after a pick drops the pick. Otherwise the text and the coordinates drift
   * apart and the request routes from a place the visitor has edited away from.
   */
  it("drops a chosen place as soon as it is edited", async () => {
    stubFetch();
    const { user } = mount();

    await bothEnds(user);
    await user.type(screen.getByLabelText("From"), " annex");

    expect(submit().disabled).toBe(true);
  });

  it("says it is working while the request is in flight", async () => {
    let release;
    stubFetch({ lookup: () => new Promise((resolve) => (release = resolve)) });
    const { user } = mount();

    await bothEnds(user);
    await user.click(submit());

    expect(screen.getByRole("button", { name: /looking up/i }).disabled).toBe(true);

    release(answer(200, { ok: true }));
    expect(await screen.findByRole("button", { name: /forecast this/i })).toBeTruthy();
  });

  /**
   * The refusal the panel exists to handle well: a spent daily allowance is a normal
   * state, so it is stated in full — with the time it comes back — and not as an error.
   */
  it("states the daily limit when the service refuses a sixth lookup", async () => {
    stubFetch({
      lookup: answer(429, {
        error: "rate_limited",
        message: "Daily limit reached",
        dailyLimit: 5,
        resetsAt: "2026-09-18T07:00:00Z",
      }),
    });
    const { user, onResult, onQuotaChange } = mount({ yourRemaining: 0 });

    await bothEnds(user);
    await user.click(submit());

    const banner = await screen.findByRole("status");
    expect(banner.textContent).toContain("You’ve used your 5 lookups for today.");
    expect(banner.className).toContain("is-limit");
    expect(onResult).not.toHaveBeenCalled();
    // The counters moved even though nothing was served, so the readout is re-read.
    expect(onQuotaChange).toHaveBeenCalled();
  });

  it("keeps the form usable when the service cannot be reached", async () => {
    stubFetch({
      lookup: () => Promise.reject(new TypeError("Failed to fetch")),
    });
    const { user, onQuotaChange } = mount();

    await bothEnds(user);
    await user.click(submit());

    expect(await screen.findByRole("status")).toBeTruthy();
    // Not stranded in "Looking up…": the same two places can be sent again.
    expect(submit().disabled).toBe(false);
    expect(onQuotaChange).toHaveBeenCalled();
  });
});
