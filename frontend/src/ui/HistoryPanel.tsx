/**
 * Oś czasu dokumentu z podglądem stanu z przeszłości i przywracaniem wersji (ADR-0003).
 *
 * Przywrócenie zapisywane jest jako NOWE operacje, nie jako obcięcie logu — panel prosi
 * więc serwer o wykonanie zmiany i nie dotyka lokalnego modelu. Efekt wraca zwykłą ścieżką
 * rozgłoszenia, tak samo jak cudza edycja.
 */
import { useEffect, useState } from 'react'
import { api, type Version } from '../api'
import { formatTime } from './format'

export function HistoryPanel({ documentId, revision }: { documentId: string; revision: number }) {
  const [versions, setVersions] = useState<Version[]>([])
  const [selected, setSelected] = useState<Version | null>(null)
  const [preview, setPreview] = useState<string | null>(null)
  const [restoring, setRestoring] = useState(false)

  useEffect(() => {
    void api.history(documentId).then(setVersions)
  }, [documentId, revision])

  async function select(version: Version) {
    setSelected(version)
    setPreview(null)
    setPreview((await api.textAt(documentId, version.throughServerSeq)).text)
  }

  async function restore() {
    if (selected === null) return
    setRestoring(true)
    try {
      await api.restore(documentId, selected.throughServerSeq)
      setSelected(null)
      setPreview(null)
    } finally {
      setRestoring(false)
    }
  }

  return (
    <aside className="rightpanel">
      <div className="rp-header">Historia zmian</div>
      <div className="rp-body">
        {versions.length === 0 && <div className="rp-empty">Brak zmian.</div>}

        {versions.map((version) => (
          <div key={version.throughServerSeq}>
            <button
              className={`history-entry${
                selected?.throughServerSeq === version.throughServerSeq ? ' selected' : ''
              }`}
              onClick={() => void select(version)}
            >
              <div className="h-top">
                <span className="history-dot" style={{ background: version.color }} />
                {version.displayName}
                <span className="h-time">{formatTime(version.at)}</span>
              </div>
              <div className="h-desc">
                {version.charactersInserted > 0 && (
                  <span className="added">+{version.charactersInserted}</span>
                )}
                {version.charactersInserted > 0 && version.charactersDeleted > 0 && ' '}
                {version.charactersDeleted > 0 && (
                  <span className="removed">−{version.charactersDeleted}</span>
                )}
              </div>
            </button>

            {selected?.throughServerSeq === version.throughServerSeq && (
              <>
                <div className="history-preview">{preview ?? 'Wczytuję…'}</div>
                <button className="history-restore" onClick={restore} disabled={restoring}>
                  {restoring ? 'Przywracam…' : 'Przywróć tę wersję'}
                </button>
              </>
            )}
          </div>
        ))}
      </div>
    </aside>
  )
}
