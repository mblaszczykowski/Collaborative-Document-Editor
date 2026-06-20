import { useCallback, useEffect, useRef, useState } from 'react'
import { api, type DocumentSummary } from '../api'

/**
 * The document list, with search, renaming and a bin (CP11).
 *
 * **The content preview arrives from the server ready-made**, materialised at snapshot time. The
 * alternative would be opening every document to show its first line — a cost growing with the
 * number of documents times their size, paid on every visit to the list.
 *
 * **Deletion is soft, and the interface says so.** "Delete" moves a document to the bin; the
 * operation log is left untouched, because it is the source of truth (ADR-0003). If the button
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

  // The query goes out only once typing stops. Without this, every keystroke in the search box is
  // a separate full-text query — exactly the pattern that, in cursor broadcasting, cost us traffic
  // growing with the square of the number of people.
  useEffect(() => {
    if (debounce.current !== null) window.clearTimeout(debounce.current)
    if (query.trim() === '') {
      setResults(null)
      return
    }
    debounce.current = window.setTimeout(() => {
      void api.searchDocuments(query.trim()).then(setResults)
    }, SEARCH_DEBOUNCE_MS)
    return () => {
      if (debounce.current !== null) window.clearTimeout(debounce.current)
    }
  }, [query])

  const loadTrash = useCallback(() => {
    void api.listTrash().then(setTrash)
  }, [])

  useEffect(() => {
    if (showTrash) loadTrash()
  }, [showTrash, loadTrash])

  async function rename(id: string, title: string) {
    setRenaming(null)
    if (title.trim() === '') return
    await api.renameDocument(id, title.trim())
    props.onChanged()
  }

  async function remove(id: string) {
    await api.deleteDocument(id)
    props.onChanged()
  }

  async function restore(id: string) {
    await api.restoreDocument(id)
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
