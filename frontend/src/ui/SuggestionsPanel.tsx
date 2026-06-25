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
import { useCallback, useState } from 'react'
import { api, type Suggestion } from '../api'
import { FailureNote } from './FailureNote'
import { usePanelData } from './usePanelData'

/** Module-level, so the initial value is not a new array on every render. */
const NO_SUGGESTIONS: Suggestion[] = []

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

  const [draft, setDraft] = useState('')
  const [busy, setBusy] = useState(false)

  // The debounce, the stale guard and the swallowed 404 come from `usePanelData`. This panel had
  // the first and the third and NOT the second, so a slow answer for an earlier revision could
  // land after a newer one and leave a proposal list that does not match the document.
  const panel = usePanelData({
    load: useCallback(() => api.listSuggestions(documentId), [documentId]),
    revision,
    initial: NO_SUGGESTIONS,
    describe: 'the suggestions',
  })
  const suggestions = panel.data
  const { failure } = panel

  /**
   * Runs one of the panel's gestures, and says whether it worked.
   *
   * The return value is the fix for a real loss of work: the caller used to write
   * `void run(...).then(() => setDraft(''))`, and since `run` swallows the error, `.then` fired
   * whatever happened. So a proposal the server refused cleared the user's text AND told them it
   * had failed — the interface taking the one copy of the words it had just said it could not
   * send.
   *
   * The message and the console line now come from `useFailure`, which is where the rest of the
   * interface reports a gesture that did not take effect — this panel and `CommentsPanel` each
   * had their own `failed` state, and this one rendered it as a `<p>` the user cannot dismiss.
   *
   * The refresh has also moved OUT of the attempt. It used to sit inside the try, so a proposal
   * that was accepted and then failed to re-list told the user "that text is no longer there"
   * about a change the server had already made.
   */
  async function run(action: () => Promise<void>): Promise<boolean> {
    setBusy(true)
    try {
      // The one failure worth naming: the text a proposal was about is gone, deleted by
      // somebody editing while the proposal was being written. The server answers 409 and says
      // so rather than inventing a place to put it.
      const done = await failure.attempt(
        'That text is no longer there — somebody changed it while you were writing.',
        action,
      )
      if (done) await panel.refresh()
      return done
    } finally {
      setBusy(false)
    }
  }

  return (
    <aside className="rightpanel">
      <div className="rp-header">Suggestions</div>
      <div className="rp-body">
        {/* Above the compose box rather than inside it: accept and reject report through the same
            failure, and they are offered to somebody who may decide without necessarily being
            shown the compose box. */}
        {failure.message !== null && (
          <FailureNote message={failure.message} onDismiss={failure.dismiss} />
        )}
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
