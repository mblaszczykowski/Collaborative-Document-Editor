import { useCallback, useRef, useState } from 'react'
import { api, type DiffSegment, type Version, type WireSpan } from '../api'
import { FailureNote } from './FailureNote'
import { formatTime } from './format'
import { usePanelData } from './usePanelData'

function FormattedPreview({ text, marks = [] }: { text: string; marks?: WireSpan[] }) {
  const pieces: { text: string; marks: Record<string, string | null> }[] = []
  let cursor = 0
  for (const span of marks) {
    if (span.from > cursor) {
      pieces.push({ text: text.slice(cursor, span.from), marks: {} })
    }
    pieces.push({ text: text.slice(span.from, span.to), marks: span.marks })
    cursor = Math.max(cursor, span.to)
  }
  if (cursor < text.length) {
    pieces.push({ text: text.slice(cursor), marks: {} })
  }

  return (
    <div className="history-preview">
      {pieces.map((piece, index) => {
        let node: React.ReactNode = piece.text
        if (piece.marks.code !== undefined) node = <code>{node}</code>
        if (piece.marks.strike !== undefined) node = <s>{node}</s>
        if (piece.marks.underline !== undefined) node = <u>{node}</u>
        if (piece.marks.italic !== undefined) node = <em>{node}</em>
        if (piece.marks.bold !== undefined) node = <strong>{node}</strong>
        if (piece.marks.comment !== undefined) {
          node = <span className="preview-commented">{node}</span>
        }
        return <span key={index}>{node}</span>
      })}
    </div>
  )
}

function Comparison({ segments }: { segments: DiffSegment[] }) {
  if (segments.every((segment) => segment.kind === 'same')) {
    return <div className="history-diff empty">No changes to the content.</div>
  }
  return (
    <div className="history-diff">
      {segments.map((segment, index) => (
        <span key={index} className={`diff-${segment.kind}`}>
          {segment.text}
        </span>
      ))}
    </div>
  )
}

const NO_VERSIONS: Version[] = []

export function HistoryPanel(
  { documentId, revision, readOnly }:
    { documentId: string; revision: number; readOnly?: boolean },
) {
  const [selected, setSelected] = useState<Version | null>(null)
  const [preview, setPreview] = useState<{ text: string; marks?: WireSpan[] } | null>(null)
  const [diff, setDiff] = useState<DiffSegment[] | null>(null)
  const [restoring, setRestoring] = useState(false)
  const panel = usePanelData({
    load: useCallback(() => api.history(documentId), [documentId]),
    revision,
    initial: NO_VERSIONS,
    describe: 'the version history',
  })
  const versions = panel.data
  const failure = panel.failure

  const wanted = useRef<number | null>(null)

  async function select(version: Version) {
    setSelected(version)
    setPreview(null)
    setDiff(null)
    wanted.current = version.throughServerSeq
    await failure.attempt('Could not load that version.', async () => {
      const state = await api.textAt(documentId, version.throughServerSeq)
      if (wanted.current !== version.throughServerSeq) return
      setPreview({ text: state.text, marks: state.marks })
    })
  }

  async function compare(version: Version) {
    if (versions.length === 0) return
    const head = versions[0].throughServerSeq
    setDiff(null)
    await failure.attempt('Could not work out what has changed since then.', async () => {
      const result = await api.compare(documentId, version.throughServerSeq, head)
      if (wanted.current !== version.throughServerSeq) return
      setDiff(result.segments)
    })
  }

  async function restore() {
    if (selected === null) return
    setRestoring(true)
    try {
      const done = await failure.attempt('Could not restore that version. Nothing was changed.',
        async () => {
          await api.restore(documentId, selected.throughServerSeq)
        })
      if (!done) return
      setSelected(null)
      setPreview(null)
      setDiff(null)
    } finally {
      setRestoring(false)
    }
  }

  return (
    <aside className="rightpanel">
      <div className="rp-header">Change history</div>
      <div className="rp-body">
        {failure.message !== null && (
          <FailureNote message={failure.message} onDismiss={failure.dismiss} />
        )}
        {versions.length === 0 && <div className="rp-empty">No changes yet.</div>}

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
                {version.charactersInserted === 0 && version.charactersDeleted === 0 && (
                  <span className="formatting-only">formatting</span>
                )}
              </div>
            </button>

            {selected?.throughServerSeq === version.throughServerSeq && (
              <>
                {preview === null ? (
                  <div className="history-preview">Loading…</div>
                ) : (
                  <FormattedPreview text={preview.text} marks={preview.marks} />
                )}

                <div className="history-actions">
                  <button className="history-compare" onClick={() => void compare(version)}>
                    What changed since then
                  </button>
                  {
}
                  {!readOnly && (
                    <button
                      className="history-restore"
                      onClick={() => void restore()}
                      disabled={restoring}
                    >
                      {restoring ? 'Restoring…' : 'Restore this version'}
                    </button>
                  )}
                </div>

                {diff !== null && <Comparison segments={diff} />}
              </>
            )}
          </div>
        ))}
      </div>
    </aside>
  )
}
