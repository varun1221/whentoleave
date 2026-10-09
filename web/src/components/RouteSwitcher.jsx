import { useEffect, useRef, useState } from "react";

/**
 * The route's name is the switcher: it opens a menu of the seeded corridors, then any
 * looked up this visit, then "Add a route". One control in place of a row of tabs, so
 * adding routes never crowds the page.
 */
export default function RouteSwitcher({ routes, selectedRoute, onSelect, onAdd }) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef(null);
  const buttonRef = useRef(null);

  // Click outside or Escape closes it; Escape hands focus back to the button.
  useEffect(() => {
    if (!open) return;
    const onPointer = (e) => {
      if (!rootRef.current?.contains(e.target)) setOpen(false);
    };
    const onKey = (e) => {
      if (e.key === "Escape") {
        setOpen(false);
        buttonRef.current?.focus();
      }
    };
    document.addEventListener("pointerdown", onPointer);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("pointerdown", onPointer);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  const seeded = routes.filter((r) => !r.lookedUp);
  const lookedUp = routes.filter((r) => r.lookedUp);

  const pick = (id) => {
    setOpen(false);
    onSelect(id);
  };

  const item = (route) => {
    const selected = route.id === selectedRoute.id;
    return (
      <li key={route.id}>
        <button
          type="button"
          className={"switch-item" + (selected ? " is-selected" : "")}
          aria-current={selected ? "true" : undefined}
          onClick={() => pick(route.id)}
        >
          {route.name}
        </button>
      </li>
    );
  };

  return (
    <div className="route-switcher" ref={rootRef}>
      <h2>
        <button
          ref={buttonRef}
          type="button"
          className="route-switch"
          aria-expanded={open}
          aria-controls="route-menu"
          onClick={() => setOpen((o) => !o)}
        >
          <span className="route-switch-name">{selectedRoute.name}</span>
          <svg className="route-switch-chevron" viewBox="0 0 20 20" aria-hidden="true">
            <path d="M5 7.5l5 5 5-5" />
          </svg>
          <span className="visually-hidden">, change route</span>
        </button>
        {selectedRoute.partial && <span className="badge">Weekday peaks</span>}
      </h2>

      {open && (
        <div className="switch-menu" id="route-menu">
          <ul>{seeded.map(item)}</ul>
          {lookedUp.length > 0 && (
            <>
              <p className="switch-group">Your lookups</p>
              <ul className="is-lookups">{lookedUp.map(item)}</ul>
            </>
          )}
          {onAdd && (
            <button
              type="button"
              className="switch-add"
              onClick={() => {
                setOpen(false);
                onAdd();
              }}
            >
              <span aria-hidden="true">+</span> Add a route
            </button>
          )}
        </div>
      )}
    </div>
  );
}
