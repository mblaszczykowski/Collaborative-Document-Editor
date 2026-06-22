/**
 * Proposed changes: making them, and deciding on them (CP23).
 *
 * The panel is where the `commenter` role finally does something other than talk. Until CP23 a
 * commenter could write *about* the document and never *to* it; a suggestion is a contribution
 * that still carries no permission to change anything.
 *
 * Two capabilities are on this screen and they are deliberately not the same one:
 *
 * - **proposing** needs comment permission — the buttons at the top;
 * - **deciding** needs write permission — accept and reject on each entry.
 *
 * Everything the server refuses is simply not offered here. The refusal is real either way
 * (SuggestionIT), and this is only about not inviting a gesture that ends in a rejected request.
 */
import { useCallback, useEffect, useState } from 'react'
import { ApiError, api, type Suggestion } from '../api'
import { useDebouncedValue } from './useDebouncedValue'

export function SuggestionsPanel(props: {
  documentId: string
  /** Bumped whenever the document changes, so the list follows the text. */
  revision: number
  canPropose: boolean
  canDecide: boolean
  /** The current selection, or null — proposals are made about it. */
  selection: string
  onProposeDeletion: () => Promise<void>
  onProposeInsertion: (text: string) => Promise<void>
}) {
  const { documentId, revision, canPropose, canDecide, selection } = props

  const [suggestions, setSuggestions] = useState<Suggestion[]>([])
  const [draft, setDraft] = useState('')
  const [busy, setBusy] = useState(false)
  const [failed, setFailed] = useState<string | null>(null)

  const reload = useCallback(async () => {
    setSuggestions(await api.listSuggestions(documentId))
  }, [documentId])

  // Debounced, for the same reason as the other two panels: `revision` changes on every keystroke.
  const settledRevision = useDebouncedValue(revision)
  useEffect(() => {
    // Caught, like the other panels. A 404 here is the document being deleted or access being
    // revoked underneath an open panel, which the workspace already reports; anything else is a
    // defect and belongs in the console rather than in an unhandled rejection.
    reload().catch((error: unknown) => {
      if (error instanceof ApiError && error.status === 404) return
      console.error('could not load the suggestions', error)
    })
  }, [reload, settledRevision])

  /**
   * Runs one of the panel's gestures, and says whether it worked.
   *
   * The return value is the fix for a real loss of work: the caller used to write
   * `void run(...).then(() => setDraft(''))`, and since `run` swallows the error, `.then` fired
   * whatever happened. So a proposal the server refused cleared the user's text AND told them it
   * had failed — the interface taking the one copy of the words it had just said it could not
   * send.
   */
  async function run(action: () => Promise<void>): Promise<boolean> {
    setBusy(true)
    setFailed(null)
    try {
      await action()
      await reload()
      return true
    } catch {
      // The one failure worth naming: the text a proposal was about is gone, deleted by
      // somebody editing while the proposal was being written. The server answers 409 and says
      // so rather than inventing a place to put it.
      setFailed('That text is no longer there — somebody changed it while you were writing.')
      return false
    } finally {
      setBusy(false)
    }
  }

  return (
    <aside className="rightpanel">
      <div className="rp-header">Suggestions</div>
      <div className="rp-body">
        {canPropose && (
          <div className="suggest-compose">
            <button
              className="suggest-action"
              disabled={selection === '' || busy}
              onClick={() => void run(props.onProposeDeletion)}
            >
              {selection === ''
                ? 'Select text to propose deleting it'
                : `Propose deleting “${selection.slice(0, 24)}${selection.length > 24 ? '…' : ''}”`}
            </button>

            <input
              value={draft}
              placeholder="Propose inserting…"
              onChange={(event) => setDraft(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter' && draft !== '') {
                  // Cleared on SUCCESS only. See `run` — the previous `.then` ran unconditionally,
                  // so a refused proposal took the user's text with it.
                  void run(() => props.onProposeInsertion(draft)).then((sent) => {
                    if (sent) setDraft('')
                  })
                }
              }}
            />
            <p className="suggest-hint">
              Inserted after the selection, or at the start of the document when nothing is
              selected.
            </p>
            {failed !== null && <p className="suggest-failed">{failed}</p>}
          </div>
        )}

        {suggestions.length === 0 && <div className="rp-empty">No open suggestions.</div>}

        {suggestions.map((suggestion) => (
          <div key={suggestion.id} className="suggest-entry">
            <div className="s-top">
              <span className="history-dot" style={{ background: suggestion.authorColor }} />
              {suggestion.authorName}
              <span className={`s-kind ${suggestion.kind === 'INSERT' ? 'insert' : 'delete'}`}>
                {suggestion.kind === 'INSERT' ? 'insert' : 'delete'}
              </span>
            </div>

            <div className={`s-quote ${suggestion.kind === 'INSERT' ? 'insert' : 'delete'}`}>
              {suggestion.quotedText}
            </div>

            {!suggestion.anchored && (
              // An honest state rather than a hidden one: the proposal exists, and the text it
              // was about does not. Accepting it applies nothing, and says so instead of
              // reporting success.
              <p className="suggest-hint">
                The text this was about has been deleted. Accepting changes nothing.
              </p>
            )}

            {canDecide && (
              <div className="s-actions">
                <button
                  className="suggest-accept"
                  disabled={busy}
                  onClick={() =>
                    void run(() => api.acceptSuggestion(documentId, suggestion.id))
                  }
                >
                  Accept
                </button>
                <button
                  className="suggest-reject"
                  disabled={busy}
                  onClick={() =>
                    void run(() => api.rejectSuggestion(documentId, suggestion.id))
                  }
                >
                  Reject
                </button>
              </div>
            )}
          </div>
        ))}
      </div>
    </aside>
  )
}
