import { useEffect, useId, useRef, useState } from "react";
import { suggestPlaces } from "../lib/api.js";
import { coordSuggestion } from "../lib/places.js";

/**
 * §9.5: "debounce 300ms client-side." The service also floors the query length, but that
 * backstop still costs one request per word typed if the client fires on every letter.
 */
const DEBOUNCE_MS = 300;

/**
 * An address box that resolves to coordinates.
 *
 * The value is a suggestion (`{ coord, description }`) rather than the text, because
 * `/api/lookup` takes coordinates and the server already formats them into the exact
 * string it accepts. Typing after a selection clears it: the text and the coordinates
 * would otherwise drift apart, and the request would use a place the visitor had edited
 * away from.
 */
export default function PlaceField({ label, value, onChange, placeholder }) {
  const id = useId();
  const listboxId = `${id}-listbox`;
  // The text is this field's own state, not a mirror of `value` — the selection is
  // reported upward and only ever set from in here, so seeding from the prop would be
  // the mirroring §10 warns about with none of the benefit.
  const [text, setText] = useState("");
  const [options, setOptions] = useState([]);
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  const [searchCode, setSearchCode] = useState(null);
  const blurTimer = useRef(null);

  useEffect(() => () => clearTimeout(blurTimer.current), []);

  // One effect per settled query: the debounce timer and the abort controller are torn
  // down together, so a slow answer to an abandoned query can never overwrite a newer
  // one — the bug that makes an autocomplete flicker back to stale results.
  useEffect(() => {
    if (value && value.description === text) {
      setOptions([]);
      return undefined;
    }
    // A coordinate pair is already an answer. Searching for it would spend a request
    // from the address-search budget to be told what the visitor just typed.
    const typedCoord = coordSuggestion(text);
    if (typedCoord) {
      setOptions([typedCoord]);
      setActive(0);
      setSearchCode(null);
      return undefined;
    }
    const controller = new AbortController();
    const timer = setTimeout(() => {
      suggestPlaces(text, { signal: controller.signal }).then(({ suggestions, code }) => {
        if (controller.signal.aborted) return;
        setOptions(suggestions);
        setActive(suggestions.length > 0 ? 0 : -1);
        setSearchCode(code);
      });
    }, DEBOUNCE_MS);
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [text, value]);

  const choose = (suggestion) => {
    onChange(suggestion);
    setText(suggestion.description);
    setOptions([]);
    setOpen(false);
    setActive(-1);
  };

  const onKeyDown = (e) => {
    if (options.length === 0) return;
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setOpen(true);
      setActive((i) => (i + 1) % options.length);
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setOpen(true);
      setActive((i) => (i <= 0 ? options.length - 1 : i - 1));
    } else if (e.key === "Enter" && open && active >= 0) {
      // Only swallow Enter when a suggestion is highlighted, so Enter on a settled
      // field still submits the form.
      e.preventDefault();
      choose(options[active]);
    } else if (e.key === "Escape") {
      setOpen(false);
    }
  };

  const showList = open && options.length > 0;
  // A search that was refused is not a place that does not exist, and the difference
  // matters: one is worth retyping, the other is not. Said under the field rather than in
  // a banner, because the visitor has not asked for a forecast yet.
  const searchHint =
    searchCode === "rate_limited"
      ? "Address searches for today are used up — paste a lat,lon instead."
      : searchCode != null && searchCode !== "invalid_request"
        ? "Address search is unavailable — paste a lat,lon instead."
        : null;

  return (
    <div className="place-field">
      <label htmlFor={id}>{label}</label>
      <input
        id={id}
        type="text"
        role="combobox"
        autoComplete="off"
        aria-expanded={showList}
        aria-controls={listboxId}
        aria-activedescendant={
          showList && active >= 0 ? `${listboxId}-${active}` : undefined
        }
        placeholder={placeholder}
        value={text}
        onChange={(e) => {
          setText(e.target.value);
          setOpen(true);
          if (value) onChange(null);
        }}
        onFocus={() => setOpen(true)}
        // A click on an option blurs the input before the click lands, so closing is
        // deferred by a tick rather than on blur itself.
        onBlur={() => {
          blurTimer.current = setTimeout(() => setOpen(false), 120);
        }}
        onKeyDown={onKeyDown}
      />

      {showList && (
        <ul className="suggestions" role="listbox" id={listboxId}>
          {options.map((option, i) => (
            <li
              key={option.coord}
              id={`${listboxId}-${i}`}
              role="option"
              aria-selected={i === active}
              className={"suggestion" + (i === active ? " is-active" : "")}
              onMouseEnter={() => setActive(i)}
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => choose(option)}
            >
              {option.description}
            </li>
          ))}
        </ul>
      )}

      {searchHint && !showList && (
        <p className="field-hint" role="status">
          {searchHint}
        </p>
      )}
    </div>
  );
}
