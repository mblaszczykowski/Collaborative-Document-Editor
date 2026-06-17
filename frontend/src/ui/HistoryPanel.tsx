/**
 * The document timeline, with a preview of a past state and version restore (ADR-0003).
 *
 * A restore is written as NEW operations rather than as a truncation of the log, so this panel
 * asks the server to perform the change and never touches the local model. The effect comes back
 * through the ordinary broadcast path, exactly like somebody else's edit.
 *
 * Since CP22 the panel shows two things it did not have before:
 *
 * 1. **a preview with formatting** — until then only the text arrived, so a version whose sole
 *    change was a bolding looked identical to the one before it;
 * 2. **a comparison with the current version**, computed from character identity rather than
 *    from the text.
 */
import { useEffect, useState } from 'react'
import { api, type DiffSegment, type Span, type Version } from '../api'
import { formatTime } from './format'

/** A preview with formatting: split the text into runs and dress each one in markup. */
function FormattedPreview({ text, marks }: { text: string; marks: Span[] }) {
  const pieces: { text: string; marks: Record<string, string> }[] = []
  let cursor = 0
  // The spans arrive sorted and disjoint (MarkSet.spans), so one pass is enough — the gaps
  // between them are unformatted text.
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
        // The nesting order does not affect the result — the marks are independent.
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

export function HistoryPanel(
  { documentId, revision, readOnly }:
    { documentId: string; revision: number; readOnly?: boolean },
) {
  const [versions, setVersions] = useState<Version[]>([])
  const [selected, setSelected] = useState<Version | null>(null)
  const [preview, setPreview] = useState<{ text: string; marks: Span[] } | null>(null)
  const [diff, setDiff] = useState<DiffSegment[] | null>(null)
  const [restoring, setRestoring] = useState(false)

  useEffect(() => {
    void api.history(documentId).then(setVersions)
  }, [documentId, revision])

  async function select(version: Version) {
    setSelected(version)
    setPreview(null)
    setDiff(null)
    const state = await api.textAt(documentId, version.throughServerSeq)
    setPreview({ text: state.text, marks: state.marks })
  }

  /**
   * Compared against the CURRENT version, not the previous one.
   *
   * Somebody browsing history asks "what has changed since then", not "how did this version
   * differ from the one before it". The second question is easier to answer and is rarely the
   * one that was being asked.
   */
  async function compare(version: Version) {
    if (versions.length === 0) return
    const head = versions[0].throughServerSeq
    setDiff(null)
    const result = await api.compare(documentId, version.throughServerSeq, head)
    setDiff(result.segments)
  }

  async function restore() {
    if (selected === null) return
    setRestoring(true)
    try {
      await api.restore(documentId, selected.throughServerSeq)
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
                  // An entry with no characters is a formatting change. Until CP22 it looked
                  // like an empty entry, because both the counter and the preview spoke only
                  // about text.
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
                  {/* The preview stays, the restore button goes. A reader is entitled to SEE
                      how the document once looked — not to roll it back to that. The server
                      refuses independently (PermissionIT.historyEndpointsCheckAccess); this is
                      only about not offering a gesture that would be refused. */}
                  {!readOnly && (
                    <button className="history-restore" onClick={restore} disabled={restoring}>
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
