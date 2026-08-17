import { useCallback, useState } from 'react'
import { api, type Suggestion } from '../api'
import { FailureNote } from './FailureNote'
import { usePanelData } from './usePanelData'

const NO_SUGGESTIONS: Suggestion[] = []

export function SuggestionsPanel(props: {
  documentId: string
  revision: number
  canPropose: boolean
  canDecide: boolean
  selection: string
  onProposeDeletion: () => Promise<void>
  onProposeInsertion: (text: string) => Promise<void>
}) {
  const { documentId, revision, canPropose, canDecide, selection } = props

  const [draft, setDraft] = useState('')
  const [busy, setBusy] = useState(false)

  const panel = usePanelData({
    load: useCallback(() => api.listSuggestions(documentId), [documentId]),
    revision,
    initial: NO_SUGGESTIONS,
    describe: 'the suggestions',
  })
  const suggestions = panel.data
  const { failure } = panel

  async function run(action: () => Promise<void>): Promise<boolean> {
    setBusy(true)
    try {
      const done = await failure.attempt(
        'That text is no longer there - somebody changed it while you were writing.',
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
        {
}
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
