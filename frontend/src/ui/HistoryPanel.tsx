/**
 * The document timeline, with a preview of a past state and version restore.
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
import { useEffect, useRef, useState } from 'react'
import { useDebouncedValue } from './useDebouncedValue'
import { ApiError, api, type DiffSegment, type Version, type WireSpan } from '../api'
import { FailureNote } from './FailureNote'
import { formatTime } from './format'
import { useFailure } from './useFailure'

/**
 * A preview with formatting: split the text into runs and dress each one in markup.
 *
 * `marks` is defaulted rather than trusted, and that is not defensive programming for its own
 * sake — it is a crash this suite reproduces. A `GET /history/at` answering
 * `{"throughServerSeq":0,"text":""}` with no `marks` key at all sent `for (const span of marks)`
 * into `TypeError: marks is not iterable`, which is an UNCAUGHT error inside a render: React
 * unmounts the subtree, the preview never appears, and the panel sits on "Loading…" for ever with
 * the reason visible only in the console. The e2e scenario "history lets the document be rewound
 * to an earlier version" has been failing on exactly that.
 *
 * A version with no formatting and a response from a server that does not send the field are the
 * same picture — unstyled text — so treating them the same is right rather than merely safe. It is
 * the same rule `connection.ts` applies to a missing `last` flag: read a missing field as the
 * behaviour that was correct before it existed.
 */
function FormattedPreview({ text, marks = [] }: { text: string; marks?: WireSpan[] }) {
  // `string | null`, because a valueless mark — bold, italic, a quotation — serialises as
  // `{"bold": null}`. Every test below compares against `undefined`, which is what an ABSENT key
  // gives; that was correct by luck while the type claimed the values were always strings, and it
  // is correct on purpose now that the type admits null. See `WireSpan` in api.ts.
  const pieces: { text: string; marks: Record<string, string | null> }[] = []
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
  const [preview, setPreview] = useState<{ text: string; marks?: WireSpan[] } | null>(null)
  const [diff, setDiff] = useState<DiffSegment[] | null>(null)
  const [restoring, setRestoring] = useState(false)
  const failure = useFailure()
  // Debounced, and guarded against its own stale answers. `revision` is the character count, so
  // before this every keystroke fired a `GET /history` — and because nothing sequenced the
  // replies, a slow answer for an earlier revision could overwrite a newer one.
  const settledRevision = useDebouncedValue(revision)
  useEffect(() => {
    let stale = false
    api.history(documentId)
      .then((loaded) => {
        if (!stale) setVersions(loaded)
      })
      // Not routed through `useFailure`: this reload runs on its own, roughly every time typing
      // settles, so a message about it would appear without anybody having asked for anything and
      // would come back on the next keystroke. The console gets the detail, and the panel simply
      // keeps showing the list it already has.
      //
      // A 404 is not a defect and is not logged. The panel stays mounted while the document
      // itself is being deleted or access to it is revoked, and the server answers 404 for all
      // three of "deleted", "never existed" and "not yours" — the workspace already says so, in
      // its own banner, once. Logging it here would put a red line in the console for the
      // ordinary way this panel ends its life.
        .catch((error: unknown) => {
        if (error instanceof ApiError && error.status === 404) return
        console.error('could not load the version history', error)
      })
    return () => {
      stale = true
    }
  }, [documentId, settledRevision])

  /**
   * The version the panel is currently showing — checked by every async answer before it lands.
   *
   * A ref rather than a read of `selected`, because this is compared inside an async callback where
   * the captured state is the one from the render that started the request. History entries are the
   * one place a user clicks rapidly, scanning — so clicking A then B and having A answer more
   * slowly showed A's text under B's selection, with nothing saying so.
   */
  const wanted = useRef<number | null>(null)

  async function select(version: Version) {
    setSelected(version)
    setPreview(null)
    setDiff(null)
    wanted.current = version.throughServerSeq
    // Reported, because this one IS a gesture: the user clicked an entry and the panel then sits
    // on "Loading…" for ever if the call fails. An indefinite spinner is a claim that the answer
    // is still coming.
    await failure.attempt('Could not load that version.', async () => {
      const state = await api.textAt(documentId, version.throughServerSeq)
      if (wanted.current !== version.throughServerSeq) return
      setPreview({ text: state.text, marks: state.marks })
    })
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
    await failure.attempt('Could not work out what has changed since then.', async () => {
      const result = await api.compare(documentId, version.throughServerSeq, head)
      // The same guard the preview needs, and for the same reason: a diff is requested from the
      // expanded entry, and the user can collapse it and open another one while the answer is in
      // flight. `wanted.current` is the version `select` last put on screen.
      if (wanted.current !== version.throughServerSeq) return
      setDiff(result.segments)
    })
  }

  async function restore() {
    if (selected === null) return
    setRestoring(true)
    try {
      // The one gesture in this panel that CHANGES the document, and the one where silence was
      // most expensive: a failed restore reset nothing and said nothing, so the panel stayed
      // exactly as it was and the user could reasonably conclude the restore had happened and the
      // editor simply had not caught up. It comes back through the ordinary broadcast path, so
      // there is no local state to compare against — only this message.
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
                    <button
                      className="history-restore"
                      onClick={() => void restore}
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
