import { useCallback, useEffect, useRef, useState } from 'react'
import { api, type DocumentSummary } from '../api'

/**
 * Lista dokumentów z wyszukiwaniem, zmianą nazwy i koszem (CP11).
 *
 * **Podgląd treści przychodzi z serwera gotowy**, materializowany przy snapshotowaniu.
 * Alternatywą byłoby otwarcie każdego dokumentu, żeby pokazać jego pierwszą linijkę —
 * czyli koszt rosnący z liczbą dokumentów razy ich rozmiar, płacony przy każdym
 * wejściu na listę.
 *
 * **Usunięcie jest miękkie i tak jest opisane w interfejsie.** „Usuń" chowa dokument
 * do kosza; log operacji zostaje nietknięty, bo jest źródłem prawdy (ADR-0003). Gdyby
 * przycisk kasował naprawdę, kasowałby też całą historię dokumentu — i użytkownik nie
 * miałby jak się o tym dowiedzieć przed faktem.
 */

/** Odstęp między naciśnięciem klawisza a zapytaniem do serwera. */
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

  // Zapytanie dopiero po ustaniu pisania. Bez tego każdy klawisz w polu wyszukiwania
  // to osobne zapytanie pełnotekstowe — czyli dokładnie ten wzorzec, który przy
  // rozgłaszaniu kursorów kosztował nas ruch rosnący z kwadratem liczby osób.
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
        placeholder="Szukaj w dokumentach"
        value={query}
        onChange={(event) => setQuery(event.target.value)}
      />

      <div className="sb-section-label">
        {results === null ? 'Dokumenty' : `Znaleziono: ${results.length}`}
      </div>

      {visible.length === 0 && (
        <div className="sb-empty">
          {results === null ? 'Brak dokumentów.' : 'Nic nie pasuje.'}
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
              title="Kliknij dwukrotnie, żeby zmienić nazwę"
            >
              <span className="sb-title">{entry.title}</span>
              {entry.preview !== '' && <span className="sb-preview">{entry.preview}</span>}
            </button>
          )}
          <button
            className="sb-delete"
            onClick={() => void remove(entry.id)}
            title="Przenieś do kosza"
            aria-label={`Usuń ${entry.title}`}
          >
            ×
          </button>
        </div>
      ))}

      <button className="sb-item sb-new" onClick={props.onCreate}>
        + Nowy dokument
      </button>

      <button className="sb-trash-toggle" onClick={() => setShowTrash((on) => !on)}>
        {showTrash ? 'Ukryj kosz' : 'Kosz'}
      </button>

      {showTrash && (
        <>
          <div className="sb-section-label">Kosz</div>
          {trash.length === 0 && <div className="sb-empty">Kosz jest pusty.</div>}
          {trash.map((entry) => (
            <div key={entry.id} className="sb-row">
              <span className="sb-item deleted">{entry.title}</span>
              <button
                className="sb-restore"
                onClick={() => void restore(entry.id)}
                title="Przywróć"
                aria-label={`Przywróć ${entry.title}`}
              >
                ↺
              </button>
            </div>
          ))}
          <div className="sb-note">
            Usunięcie chowa dokument. Historia zmian zostaje nienaruszona.
          </div>
        </>
      )}
    </aside>
  )
}
