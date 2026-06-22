import { useCallback, useEffect, useRef, useState } from 'react'
import { api, type DocumentSummary } from '../api'
import { FailureNote } from './FailureNote'
import { useFailure } from './useFailure'

/**
 * The document list, with search, renaming and a bin (CP11).
 *
 * **The content preview arrives from the server ready-made**, materialised at snapshot time. The
 * alternative would be opening every document to show its first line — a cost growing with the
 * number of documents times their size, paid on every visit to the list.
 *
 * **Deletion is soft, and the interface says so.** "Delete" moves a document to the bin; the
 * operation log is left untouched, because it is the source of truth. If the button
 * really deleted, it would also erase the document's entire history — and the user would have no
 * way of learning that beforehand.
 */

/** The interval between a keystroke and a query to the server. */
const SEARCH_DEBOUNCE_MS = 200

export function DocumentList(props: {
  documents: DocumentSummary[]
  openId: string | null
  onSelect: (id: string) => void
  onCreate: () => void
  onChanged: () => void
}) {
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<DocumentSummary[] | null>(null)
  const [showTrash, setShowTrash] = useState(false)
  const [trash, setTrash] = useState<DocumentSummary[]>([])
  const [renaming, setRenaming] = useState<string | null>(null)
  const debounce = useRef<number | null>(null)
  const failure = useFailure()
  const { attempt } = failure

  // The query goes out only once typing stops. Without this, every keystroke in the search box is
  // a separate full-text query — exactly the pattern that, in cursor broadcasting, cost us traffic
  // growing with the square of the number of people.
  useEffect(() => {
    if (debounce.current !== null) window.clearTimeout(debounce.current)
    if (query.trim() === '') {
      setResults(null)
      return
    }
    /**
     * The guard against this effect's OWN stale answers.
     *
     * Debouncing does not fix out-of-order responses and `useDebouncedValue`'s own documentation
     * says so, naming this exact failure as the thing it does not cover. `HistoryPanel` carries
     * the guard; this effect, which is where the pattern was first written down in this
     * repository, did not.
     *
     * The failure is concrete: type "rep", pause long enough for the query to go out, type "ort".
     * If the answer for "rep" comes back after the answer for "report", the list shows matches for
     * "rep" while the box reads "report" — a search result set that does not correspond to the
     * search, with nothing to indicate it. The count in the section label makes it look
     * authoritative.
     */
    let stale = false
    debounce.current = window.setTimeout(() => {
      api.searchDocuments(query.trim())
        .then((found) => {
          if (!stale) setResults(found)
        })
        // Not a message: search runs on its own as the user types, and interrupting them with a
        // banner for a query they may already have abandoned is worse than leaving the previous
        // results up. The console keeps the detail.
          .catch((error: unknown) => console.error('the search could not be run', error))
    }, SEARCH_DEBOUNCE_MS)
    return () => {
      stale = true
      if (debounce.current !== null) window.clearTimeout(debounce.current)
    }
  }, [query])

  const loadTrash = useCallback(() => {
    void attempt('Could not load the bin.', async () => setTrash(await api.listTrash()))
  }, [attempt])

  useEffect(() => {
    if (showTrash) loadTrash()
  }, [showTrash, loadTrash])

  // Each of the four below used to be an unhandled rejection. Renaming is the one that lies
  // hardest: the input closes on the way in, so the old title comes back on the next refresh and
  // the user reads it as their new name having been ignored rather than refused.
  async function rename(id: string, title: string) {
    setRenaming(null)
    if (title.trim() === '') return
    await attempt('Could not rename the document.', async () => {
      await api.renameDocument(id, title.trim())
    })
    props.onChanged()
  }

  async function remove(id: string) {
    await attempt('Could not move the document to the bin.', async () => {
      await api.deleteDocument(id)
    })
    props.onChanged()
  }

  async function restore(id: string) {
    await attempt('Could not restore the document from the bin.', async () => {
      await api.restoreDocument(id)
    })
    loadTrash()
    props.onChanged()
  }

  const visible = results ?? props.documents

  return (
    <aside className="sidebar">
      <input
        className="sb-search"
        type="search"
        placeholder="Search documents"
        value={query}
        onChange={(event) => setQuery(event.target.value)}
      />

      <div className="sb-section-label">
        {results === null ? 'Documents' : `Found: ${results.length}`}
      </div>

      {failure.message !== null && (
        <FailureNote message={failure.message} onDismiss={failure.dismiss} />
      )}

      {visible.length === 0 && (
        <div className="sb-empty">
          {results === null ? 'No documents.' : 'Nothing matches.'}
        </div>
      )}

      {visible.map((entry) => (
        <div key={entry.id} className="sb-row">
          {renaming === entry.id ? (
            <input
              className="sb-rename"
              defaultValue={entry.title}
              autoFocus
              onBlur={(event) => void rename(entry.id, event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') void rename(entry.id, event.currentTarget.value)
                if (event.key === 'Escape') setRenaming(null)
              }}
            />
          ) : (
            <button
              className={`sb-item${entry.id === props.openId ? ' active' : ''}`}
              onClick={() => props.onSelect(entry.id)}
              onDoubleClick={() => setRenaming(entry.id)}
              title="Double-click to rename"
            >
              <span className="sb-title">{entry.title}</span>
              {entry.preview !== '' && <span className="sb-preview">{entry.preview}</span>}
            </button>
          )}
          {/* Renaming needs a control of its own.
              It used to be double-click on the entry plus a `title` saying so, which means there
              was NO keyboard route to it at all: `dblclick` has no keyboard equivalent, Enter and
              Space on that button open the document, and a `title` attribute is a mouse hint that
              a screen reader may never announce. A capability reachable only by pointer is a
              capability half the users do not have — the same argument the delete button's own
              `:focus-visible` rule already makes two files away in styles.css.

              The double-click stays: it is the faster gesture for anyone who knows it, and this
              button is what makes it discoverable rather than folklore. */}
          <button
            className="sb-edit"
            onClick={() => setRenaming(entry.id)}
            title="Rename"
            aria-label={`Rename ${entry.title}`}
          >
            ✎
          </button>
          <button
            className="sb-delete"
            onClick={() => void remove(entry.id)}
            title="Move to the bin"
            aria-label={`Delete ${entry.title}`}
          >
            ×
          </button>
        </div>
      ))}

      <button className="sb-item sb-new" onClick={props.onCreate}>
        + New document
      </button>

      <button className="sb-trash-toggle" onClick={() => setShowTrash((on) => !on)}>
        {showTrash ? 'Hide the bin' : 'Bin'}
      </button>

      {showTrash && (
        <>
          <div className="sb-section-label">Bin</div>
          {trash.length === 0 && <div className="sb-empty">The bin is empty.</div>}
          {trash.map((entry) => (
            <div key={entry.id} className="sb-row">
              <span className="sb-item deleted">{entry.title}</span>
              <button
                className="sb-restore"
                onClick={() => void restore(entry.id)}
                title="Restore"
                aria-label={`Restore ${entry.title}`}
              >
                ↺
              </button>
            </div>
          ))}
          <div className="sb-note">
            Deleting hides a document. Its change history is left intact.
          </div>
        </>
      )}
    </aside>
  )
}
