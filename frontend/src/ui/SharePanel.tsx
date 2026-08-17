import { useCallback, useEffect, useRef, useState } from 'react'
import { api, type AccessState, type DocumentState } from '../api'
import { FailureNote } from './FailureNote'
import { copyText } from './browser'
import { useFailure } from './useFailure'

type Role = DocumentState['role']

const INVITABLE: { value: Role; label: string; hint: string }[] = [
  { value: 'viewer', label: 'Can view', hint: 'Reads the document and sees who else is here.' },
  { value: 'commenter', label: 'Can comment', hint: 'Adds comments; cannot change the text.' },
  { value: 'editor', label: 'Can edit', hint: 'Full editing, including formatting.' },
]

export function SharePanel(props: {
  documentId: string
  onClose: () => void
}) {
  const { documentId, onClose } = props

  const [state, setState] = useState<AccessState | null>(null)
  const [inviteRole, setInviteRole] = useState<Role>('editor')
  const [freshLink, setFreshLink] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)
  const [busy, setBusy] = useState(false)
  const [copyByHand, setCopyByHand] = useState(false)
  const failure = useFailure()
  const { attempt } = failure
  const panel = useRef<HTMLDivElement>(null)

  const reload = useCallback(async () => {
    await attempt('Could not load who has access to this document.', async () => {
      setState(await api.access(documentId))
    })
  }, [documentId, attempt])

  useEffect(() => {
    void reload()
  }, [reload])

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null
    panel.current?.focus()

    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Tab' || panel.current === null) return
      const focusable = [
        ...panel.current.querySelectorAll<HTMLElement>(
          'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])',
),
      ].filter((element) => !element.hasAttribute('disabled'))
      if (focusable.length === 0) return
      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      } else if (event.shiftKey
        && (document.activeElement === first || document.activeElement === panel.current)) {
        event.preventDefault()
        last.focus()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('keydown', onKey)
      opener?.focus?.()
    }
  }, [])

  async function createInvite() {
    setBusy(true)
    try {
      await attempt('Could not create the invitation link.', async () => {
        const invite = await api.createInvite(documentId, inviteRole)
        const url = new URL(window.location.href)
        url.searchParams.set('doc', documentId)
        url.searchParams.set('invite', invite.token)
        setFreshLink(url.toString())
        setCopyByHand(false)
      })
      await reload()
    } finally {
      setBusy(false)
    }
  }

  async function copy(link: string) {
    if (await copyText(link)) {
      setCopied(true)
      setCopyByHand(false)
      setTimeout(() => setCopied(false), 2000)
      return
    }
    setCopyByHand(true)
    const field = panel.current?.querySelector<HTMLInputElement>('.share-fresh input')
    field?.focus()
    field?.select()
  }

  async function changeRole(userId: string, role: Role) {
    if (await attempt('Could not change that person’s role.', async () => {
      await api.setMemberRole(documentId, userId, role)
    })) {
      await reload()
    }
  }

  async function remove(userId: string) {
    if (await attempt('Could not remove that person from the document.', async () => {
      await api.removeMember(documentId, userId)
    })) {
      await reload()
    }
  }

  async function setVisibility(visibility: 'private' | 'link') {
    if (await attempt('Could not change who can open this document.', async () => {
      await api.setVisibility(documentId, visibility)
    })) {
      await reload()
    }
  }

  async function revokeInvite(inviteId: string) {
    if (await attempt('Could not revoke that invitation link - it may still work.',
      async () => {
        await api.revokeInvite(documentId, inviteId)
      })) {
      await reload()
    }
  }

  return (
    <div className="share-backdrop" onClick={onClose}>
      <div
        className="share-panel"
        role="dialog"
        aria-modal="true"
        aria-label="Share this document"
        tabIndex={-1}
        ref={panel}
        onClick={(event) => event.stopPropagation()}
      >
        <header className="share-head">
          <h2>Share</h2>
          <button className="share-close" onClick={onClose} aria-label="Close">
            ×
          </button>
        </header>

        {failure.message !== null && (
          <FailureNote message={failure.message} onDismiss={failure.dismiss} />
        )}

        {state === null ? (
          <p className="share-loading">Loading…</p>
        ) : (
          <>
            <section className="share-section">
              <h3>Invite by link</h3>
              <p className="share-hint">
                {INVITABLE.find((option) => option.value === inviteRole)?.hint}
              </p>
              <div className="share-row">
                {
}
                <select
                  aria-label="Role for the invitation link"
                  value={inviteRole}
                  onChange={(event) => setInviteRole(event.target.value as Role)}
                >
                  {INVITABLE.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
                <button className="btn-outline" onClick={() => void createInvite()} disabled={busy}>
                  Create link
                </button>
              </div>

              {freshLink !== null && (
                <div className="share-fresh">
                  <input readOnly value={freshLink} onFocus={(e) => e.target.select()} />
                  <button className="btn-outline" onClick={() => void copy(freshLink)}>
                    {copied ? 'Copied' : 'Copy'}
                  </button>
                  <p className="share-hint">
                    Shown once. The server keeps only a hash of it, so this panel can list the
                    invitation later but never show the link again.
                  </p>
                  {copyByHand && (
                    <p className="share-hint">
                      This browser will not let a page write to the clipboard here. The link is
                      selected above - copy it with ⌘C or Ctrl+C.
                    </p>
                  )}
                </div>
              )}

              {state.invites.length > 0 && (
                <ul className="share-list">
                  {state.invites.map((invite) => (
                    <li key={invite.id}>
                      <span>
                        Link · {INVITABLE.find((o) => o.value === invite.role)?.label ?? invite.role}
                      </span>
                      <span className="share-meta">
                        until {new Date(invite.expiresAt).toLocaleDateString()}
                      </span>
                      <button
                        className="share-action"
                        onClick={() => void revokeInvite(invite.id)}
                      >
                        Revoke
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </section>

            <section className="share-section">
              <h3>People with access</h3>
              <ul className="share-list">
                <li>
                  <span>You</span>
                  <span className="share-meta">Owner</span>
                </li>
                {state.members.map((member) => (
                  <li key={member.userId}>
                    <span>{member.displayName}</span>
                    <select
                      aria-label={`Role for ${member.displayName}`}
                      value={member.role}
                      onChange={(event) =>
                        void changeRole(member.userId, event.target.value as Role)
                      }
                    >
                      {INVITABLE.map((option) => (
                        <option key={option.value} value={option.value}>
                          {option.label}
                        </option>
                      ))}
                    </select>
                    <button className="share-action" onClick={() => void remove(member.userId)}>
                      Remove
                    </button>
                  </li>
                ))}
              </ul>
              {state.members.length === 0 && (
                <p className="share-hint">Nobody else yet. The link above is how they get here.</p>
              )}
            </section>

            <section className="share-section">
              <h3>General access</h3>
              <div className="share-row">
                <select
                  aria-label="Who can open this document"
                  value={state.visibility}
                  onChange={(event) =>
                    void setVisibility(event.target.value as 'private' | 'link')
                  }
                >
                  <option value="private">Restricted - only people listed above</option>
                  <option value="link">Anyone signed in with the link can edit</option>
                </select>
              </div>
              <p className="share-hint">
                {state.visibility === 'private'
                  ? 'This is the default for new documents.'
                  : 'Anybody who signs in and has the address becomes an editor. Use an invite link instead when the role matters.'}
              </p>
            </section>
          </>
        )}
      </div>
    </div>
  )
}
