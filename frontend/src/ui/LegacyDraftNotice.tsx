import { useEffect, useState } from 'react'
import { api } from '../api'
import { RgaDocument } from '../crdt/document'
import type { Op } from '../crdt/types'
import { offlineStore, type QueuedComment } from '../transport/offlineStore'
import { FailureNote } from './FailureNote'
import { useFailure } from './useFailure'

// Legacy drafts have no account. Never automatically submit them as a new user.
export function LegacyDraftNotice({ documentId, model, onRecovered }: {
  documentId: string; model: RgaDocument | null; onRecovered: (id: string) => void
}) {
  const [legacy, setLegacy] = useState<{ operations: Op[]; comments: QueuedComment[] } | null>(null)
  const [busy, setBusy] = useState(false)
  const failure = useFailure()
  useEffect(() => {
    let cancelled = false
    void Promise.all([offlineStore.pending(documentId), offlineStore.pendingComments(documentId)])
      .then(([operations, comments]) => {
        if (!cancelled && (operations.length > 0 || comments.length > 0)) setLegacy({ operations, comments })
      }).catch(() => {})
    return () => { cancelled = true }
  }, [documentId])
  if (!legacy) return null

  function backup() {
    const url = URL.createObjectURL(new Blob([JSON.stringify({ format: 'collabedit-legacy-draft', documentId, ...legacy }, null, 2)],
      { type: 'application/json' }))
    const download = document.createElement('a')
    download.href = url
    download.download = `legacy-draft-${documentId}.json`
    download.click()
    window.setTimeout(() => URL.revokeObjectURL(url), 1_000)
  }

  async function recover() {
    if (!model || !legacy || busy) return
    setBusy(true)
    try {
      await failure.attempt('Could not recover the older draft. Its local backup has been kept.', async () => {
        const draft = new RgaDocument(0)
        draft.restoreFrom(model.cachedState())
        draft.applyAll(legacy.operations)
        if (draft.pendingCount > 0) {
          failure.report('Some older edits reference unavailable text. Download the backup before recovering any remaining text.')
          return
        }
        const marks = draft.formatting().map((span) => ({ from: span.from, to: span.to,
          marks: Object.fromEntries([...span.marks].filter(([type]) => type !== 'comment' && !type.startsWith('suggest-'))) }))
        const created = await api.createRecovered('Recovered older draft', draft.text, marks)
        onRecovered(created.id)
      })
    } finally { setBusy(false) }
  }

  return <div className="recovery-banner" role="status">
    <strong>Older local drafts are available.</strong> Their account was not recorded, so they have not been sent.
    Recover them only if they belong to you. Comments remain in the downloadable backup.
    <div><button onClick={backup}>Download older draft backup</button>{' '}
      <button disabled={!model || legacy.operations.length === 0 || busy} onClick={() => void recover()}>
        {busy ? 'Recovering…' : 'Recover text into a new private document'}
      </button></div>
    {failure.message && <FailureNote message={failure.message} onDismiss={failure.dismiss} />}
  </div>
}
