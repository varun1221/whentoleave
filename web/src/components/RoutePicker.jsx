export default function RoutePicker({ routes, selectedRouteId, onSelect }) {
  return (
    <div className="route-picker" role="tablist" aria-label="Corridor">
      {routes.map((route) => (
        <button
          key={route.id}
          type="button"
          role="tab"
          aria-selected={route.id === selectedRouteId}
          className={"route-tab" + (route.id === selectedRouteId ? " is-selected" : "")}
          onClick={() => onSelect(route.id)}
        >
          {route.name}
        </button>
      ))}
    </div>
  );
}
