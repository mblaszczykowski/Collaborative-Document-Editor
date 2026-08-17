import { useCallback, useEffect, useRef, useState } from 'react'
import { api, type DocumentSummary } from '../api'
import { FailureNote } from './FailureNote'
import { useFailure } from './useFailure'

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

  useEffect(() => {
    if (debounce.current !== null) window.clearTimeout(debounce.current)
    if (query.trim() === '') {
      setResults(null)
      return
    }
    let stale = false
    debounce.current = window.setTimeout(() => {
      api.searchDocuments(query.trim())
        .then((found) => {
          if (!stale) setResults(found)
        })
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
          {
}
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
