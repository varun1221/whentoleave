/**
 * The seeded corridors, then any looked up this visit, as one row of tabs. It scrolls
 * sideways rather than wrapping, so adding a route never pushes the page down.
 */
export default function RoutePicker({ routes, selectedRouteId, onSelect }) {
  return (
    <div className="route-picker" role="tablist" aria-label="Corridor">
      {routes.map((route) => {
        const selected = route.id === selectedRouteId;
        return (
          <button
            key={route.id}
            type="button"
            role="tab"
            aria-selected={selected}
            className={
              "route-tab" +
              (selected ? " is-selected" : "") +
              (route.lookedUp ? " is-lookup" : "")
            }
            onClick={() => onSelect(route.id)}
          >
            {route.name}
          </button>
        );
      })}
    </div>
  );
}
