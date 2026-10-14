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
  onRenamed?: (id: string, title: string) => void
  openRole?: 'owner' | 'editor' | 'commenter' | 'viewer'
  creating?: boolean
  onClose?: () => void
}) {
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<DocumentSummary[] | null>(null)
  const [showTrash, setShowTrash] = useState(false)
  const [trash, setTrash] = useState<DocumentSummary[]>([])
  const [renaming, setRenaming] = useState<string | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [searching, setSearching] = useState(false)
  const [removed, setRemoved] = useState<DocumentSummary | null>(null)
  const cancelledRename = useRef(false)
  const debounce = useRef<number | null>(null)
  const failure = useFailure()
  const { attempt, report } = failure

  useEffect(() => {
    if (debounce.current !== null) window.clearTimeout(debounce.current)
    if (query.trim() === '') {
      setResults(null)
      setSearching(false)
      return
    }
    let stale = false
    debounce.current = window.setTimeout(() => {
      setSearching(true)
      api.searchDocuments(query.trim())
        .then((found) => {
          if (!stale) setResults(found)
        })
        .catch(() => { if (!stale) report('Could not search documents. Try again when connected.') })
        .finally(() => { if (!stale) setSearching(false) })
    }, SEARCH_DEBOUNCE_MS)
    return () => {
      stale = true
      if (debounce.current !== null) window.clearTimeout(debounce.current)
    }
  }, [query, props.documents, report])

  const loadTrash = useCallback(() => {
    void attempt('Could not load the bin.', async () => setTrash(await api.listTrash()))
  }, [attempt])

  useEffect(() => {
    if (showTrash) loadTrash()
  }, [showTrash, loadTrash])

  async function rename(id: string, title: string) {
    if (cancelledRename.current) { cancelledRename.current = false; return }
    if (busy !== null) return
    if (title.trim() === '') return
    setBusy(id)
    const done = await attempt('Could not rename the document.', async () => {
      await api.renameDocument(id, title.trim())
    })
    setBusy(null)
    if (done) { setRenaming(null); props.onRenamed?.(id, title.trim()); props.onChanged() }
  }

  async function remove(id: string) {
    if (busy !== null) return
    setBusy(id)
    const done = await attempt('Could not move the document to the bin.', async () => {
      await api.deleteDocument(id)
    })
    setBusy(null)
    if (done) {
      setRemoved(props.documents.find((entry) => entry.id === id) ??
        { id, title: 'Document', workspaceId: null, preview: '', updatedAt: '', role: props.openRole })
      props.onChanged()
    }
  }

  async function restore(id: string) {
    if (busy !== null) return
    setBusy(id)
    const done = await attempt('Could not restore the document from the bin.', async () => {
      await api.restoreDocument(id)
    })
    setBusy(null)
    if (done) { setRemoved(null); loadTrash(); props.onChanged() }
  }

  const visible = results ?? props.documents
  const visibleTrash = removed && !trash.some((entry) => entry.id === removed.id) ? [removed, ...trash] : trash

  return (
    <aside className="sidebar" aria-label="Documents">
      <button className="sidebar-close" aria-label="Close documents" onClick={props.onClose}>×</button>
      <input
        className="sb-search"
        type="search"
        placeholder="Search documents"
        aria-label="Search documents"
        maxLength={256}
        value={query}
        onChange={(event) => setQuery(event.target.value)}
      />

      <div className="sb-section-label">
        {searching ? 'Searching…' : results === null ? 'Documents' : `Found: ${results.length}`}
      </div>

      {failure.message !== null && (
        <FailureNote message={failure.message} onDismiss={failure.dismiss} />
      )}
      {removed && <div className="sb-note" role="status">{removed.title} moved to the bin.
        <button className="linklike" disabled={busy !== null} onClick={() => void restore(removed.id)}>Undo</button></div>}

      {visible.length === 0 && (
        <div className="sb-empty">
          {results === null ? 'No documents.' : 'Nothing matches.'}
        </div>
      )}

      {visible.map((entry) => {
        const role = entry.role ?? (entry.id === props.openId ? props.openRole : undefined)
        const canManage = role === 'owner' || role === 'editor'
        return (
        <div key={entry.id} className="sb-row">
          {renaming === entry.id ? (
            <input
              className="sb-rename"
              defaultValue={entry.title}
              aria-label={`New title for ${entry.title}`}
              maxLength={256}
              disabled={busy === entry.id}
              autoFocus
              onBlur={(event) => void rename(entry.id, event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') event.currentTarget.blur()
                if (event.key === 'Escape') { cancelledRename.current = true; setRenaming(null) }
              }}
            />
          ) : (
            <button
              className={`sb-item${entry.id === props.openId ? ' active' : ''}`}
              onClick={() => props.onSelect(entry.id)}
              onDoubleClick={() => { if (canManage) setRenaming(entry.id) }}
              title={canManage ? 'Double-click to rename' : entry.title}
              aria-current={entry.id === props.openId ? 'page' : undefined}
            >
              <span className="sb-title">{entry.title}</span>
              {entry.preview !== '' && <span className="sb-preview">{entry.preview}</span>}
            </button>
          )}
          {
}
          {canManage && <button
            className="sb-edit"
            onClick={() => setRenaming(entry.id)}
            title="Rename"
            aria-label={`Rename ${entry.title}`}
            disabled={busy !== null}
          >
            ✎
          </button>}
          {canManage && <button
            className="sb-delete"
            onClick={() => void remove(entry.id)}
            title="Move to the bin"
            aria-label={`Delete ${entry.title}`}
            disabled={busy !== null}
          >
            ×
          </button>}
        </div>
      )})}

      <button className="sb-item sb-new" onClick={props.onCreate} disabled={props.creating}>
        {props.creating ? 'Creating…' : '+ New document'}
      </button>

      <button className="sb-trash-toggle" onClick={() => setShowTrash((on) => !on)}>
        {showTrash ? 'Hide the bin' : 'Bin'}
      </button>

      {showTrash && (
        <>
          <div className="sb-section-label">Bin</div>
          {visibleTrash.length === 0 && <div className="sb-empty">The bin is empty.</div>}
          {visibleTrash.map((entry) => (
            <div key={entry.id} className="sb-row">
              <span className="sb-item deleted">{entry.title}</span>
              {(entry.role === 'owner' || entry.role === 'editor') && <button
                className="sb-restore"
                onClick={() => void restore(entry.id)}
                title="Restore"
                aria-label={`Restore ${entry.title}`}
                disabled={busy !== null}
              >
                ↺
              </button>}
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
