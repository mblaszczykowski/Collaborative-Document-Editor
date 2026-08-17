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
