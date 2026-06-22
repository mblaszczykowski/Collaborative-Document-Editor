/**
 * The message from `useFailure`, drawn.
 *
 * A separate file from the hook rather than one module exporting both, because `oxlint`'s
 * `react/only-export-components` is on and a module that exports a component alongside anything
 * else breaks fast refresh — the same reason `SaveIndicator` is its own file.
 *
 * `role="status"` rather than `role="alert"`: this reports a gesture that did not take effect, not
 * a change in the document's state, so it should be announced when the screen reader next settles
 * rather than interrupting whatever it is reading. The same choice the notice banner makes.
 *
 * Dismissible, because it does not clear itself. A failure that fades away is one the user can
 * miss, and the whole point of this component is that the failure was already invisible once.
 */
export function FailureNote({ message, onDismiss }: { message: string; onDismiss: () => void }) {
  return (
    <p className="failure-note" role="status">
      <span>{message}</span>
      <button className="failure-dismiss" onClick={onDismiss} aria-label="Dismiss">
        ×
      </button>
    </p>
  )
}
