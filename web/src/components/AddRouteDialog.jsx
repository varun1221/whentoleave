import { useEffect, useRef } from "react";

/**
 * The lookup form, out of the page and in a modal. It stays mounted while closed, so
 * a lookup still running when the dialog is dismissed keeps its state and still lands.
 */
export default function AddRouteDialog({ open, onClose, children }) {
  const ref = useRef(null);

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal?.();
    if (!open && dialog.open) dialog.close?.();
  }, [open]);

  return (
    <dialog
      ref={ref}
      className="route-dialog"
      aria-labelledby="route-dialog-title"
      // Escape closes a native dialog by itself; keep React's state in step with it.
      onClose={onClose}
      // A click on the backdrop lands on the dialog element itself, never its content.
      onClick={(e) => e.target === e.currentTarget && onClose()}
    >
      <div className="route-dialog-body">
        <div className="route-dialog-head">
          <h3 id="route-dialog-title">Add a route</h3>
          <button
            type="button"
            className="route-dialog-close"
            aria-label="Close"
            onClick={onClose}
          >
            ×
          </button>
        </div>
        {children}
      </div>
    </dialog>
  );
}
