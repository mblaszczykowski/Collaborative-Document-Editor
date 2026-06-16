import { useCallback, useEffect, useState } from 'react'
import { api, type AccessState, type DocumentState } from '../api'

/**
 * Who may open this document, and how somebody new gets in (CP21).
 *
 * The panel is deliberately thin — the interesting half of this feature is on the server. What it
 * has to get right is one thing the API cannot: **making the link the primary gesture**. Logging in
 * has no password and mints a new identifier every time, so there is nothing to type into a
 * "share with…" box; a person becomes addressable only once they have arrived. An invitation
 * carries the role and binds identity at redemption (ADR-0014).
 *
 * Everything visible here needs `owner`, so the panel is never rendered for anybody else — the
 * server would refuse each of these calls with a 404, which reads as "this document does not
 * exist" and would be a confusing thing to show somebody looking at it.
 */

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

  const reload = useCallback(async () => {
    setState(await api.access(documentId))
  }, [documentId])

  useEffect(() => {
    void reload()
  }, [reload])

  // Escape closes. Without it the only way out of a modal is the mouse, which is wrong for a
  // panel that opens from a keyboard-reachable button.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  async function createInvite() {
    setBusy(true)
    try {
      const invite = await api.createInvite(documentId, inviteRole)
      // The token is shown ONCE — the server stores only its hash, exactly like a password
      // reset link. Re-opening this panel lists the invitation but cannot show the link again.
      const url = new URL(window.location.href)
      url.searchParams.set('doc', documentId)
      url.searchParams.set('invite', invite.token)
      setFreshLink(url.toString())
      await reload()
    } finally {
      setBusy(false)
    }
  }

  async function copy(link: string) {
    await navigator.clipboard.writeText(link)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  async function changeRole(userId: string, role: Role) {
    await api.setMemberRole(documentId, userId, role)
    await reload()
  }

  async function remove(userId: string) {
    await api.removeMember(documentId, userId)
    await reload()
  }

  async function setVisibility(visibility: 'private' | 'link') {
    await api.setVisibility(documentId, visibility)
    await reload()
  }

  return (
    <div className="share-backdrop" onClick={onClose}>
      <div
        className="share-panel"
        role="dialog"
        aria-label="Share this document"
        onClick={(event) => event.stopPropagation()}
      >
        <header className="share-head">
          <h2>Share</h2>
          <button className="share-close" onClick={onClose} aria-label="Close">
            ×
          </button>
        </header>

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
                <select
                  value={inviteRole}
                  onChange={(event) => setInviteRole(event.target.value as Role)}
                >
                  {INVITABLE.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
                <button className="btn-outline" onClick={createInvite} disabled={busy}>
                  Create link
                </button>
              </div>

              {freshLink !== null && (
                <div className="share-fresh">
                  <input readOnly value={freshLink} onFocus={(e) => e.target.select()} />
                  <button className="btn-outline" onClick={() => copy(freshLink)}>
                    {copied ? 'Copied' : 'Copy'}
                  </button>
                  <p className="share-hint">
                    Shown once. The server keeps only a hash of it, so this panel can list the
                    invitation later but never show the link again.
                  </p>
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
                        onClick={async () => {
                          await api.revokeInvite(documentId, invite.id)
                          await reload()
                        }}
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
                      value={member.role}
                      onChange={(event) => changeRole(member.userId, event.target.value as Role)}
                    >
                      {INVITABLE.map((option) => (
                        <option key={option.value} value={option.value}>
                          {option.label}
                        </option>
                      ))}
                    </select>
                    <button className="share-action" onClick={() => remove(member.userId)}>
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
                  value={state.visibility}
                  onChange={(event) =>
                    setVisibility(event.target.value as 'private' | 'link')
                  }
                >
                  <option value="private">Restricted — only people listed above</option>
                  <option value="link">Anyone signed in with the link can edit</option>
                </select>
              </div>
              <p className="share-hint">
                {state.visibility === 'private'
                  ? 'This is the default for new documents since CP21.'
                  : 'Anybody who signs in and has the address becomes an editor. Use an invite link instead when the role matters.'}
              </p>
            </section>
          </>
        )}
      </div>
    </div>
  )
}
