import { useCallback, useEffect, useRef, useState } from 'react'
import { api, type AccessState, type DocumentState } from '../api'
import { FailureNote } from './FailureNote'
import { copyText } from './browser'
import { useFailure } from './useFailure'

/**
 * Who may open this document, and how somebody new gets in (CP21).
 *
 * The panel is deliberately thin — the interesting half of this feature is on the server. What it
 * has to get right is one thing the API cannot: **making the link the primary gesture**. Logging in
 * has no password and mints a new identifier every time, so there is nothing to type into a
 * "share with…" box; a person becomes addressable only once they have arrived. An invitation
 * carries the role and binds identity at redemption.
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
  /** True once a copy has failed — the link is on screen anyway, so the user copies it by hand. */
  const [copyByHand, setCopyByHand] = useState(false)
  const failure = useFailure()
  // Destructured, because `attempt` is stable across renders while the object holding it is not.
  // Depending on the object would rebuild `reload` on every render, and `reload` drives an effect.
  const { attempt } = failure
  const panel = useRef<HTMLDivElement>(null)

  const reload = useCallback(async () => {
    // A panel that cannot load its own state used to stay on "Loading…" for ever, with the failure
    // in nobody's console. It is the OWNER's view of who can read their document, so "we do not
    // know" has to be said out loud rather than shown as a spinner.
    await attempt('Could not load who has access to this document.', async () => {
      setState(await api.access(documentId))
    })
  }, [documentId, attempt])

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

  /**
   * Focus goes INTO the dialog, stays there, and comes back where it started.
   *
   * `role="dialog"` was here with none of it: `aria-modal` was missing, so a screen reader kept
   * reading the document behind the backdrop as though it were still available; focus stayed on
   * the Share button, so the first Tab went into the page underneath rather than into the panel;
   * and nothing brought focus back on close, which leaves a keyboard user at the top of the
   * document with no idea where they are. A modal that only closes for the mouse is a modal for
   * the mouse.
   *
   * Trapped by hand rather than with a library: it is one keydown handler over the focusable
   * children, the dependency would exist for one dialog, and the behaviour is small enough to
   * read here in full.
   */
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
      // Only the two edges need handling; everything in between is the browser's own order, which
      // is the right one and which we should not be reimplementing.
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
      // Back to the button that opened this, if it is still on screen. Without it, closing the
      // panel leaves focus on `<body>` and the next Tab starts from the top of the page.
      opener?.focus?.()
    }
  }, [])

  async function createInvite() {
    setBusy(true)
    try {
      await attempt('Could not create the invitation link.', async () => {
        const invite = await api.createInvite(documentId, inviteRole)
        // The token is shown ONCE — the server stores only its hash, exactly like a password
        // reset link. Re-opening this panel lists the invitation but cannot show the link again.
        const url = new URL(window.location.href)
        url.searchParams.set('doc', documentId)
        url.searchParams.set('invite', invite.token)
        setFreshLink(url.toString())
        setCopyByHand(false)
        await reload
      })
    } finally {
      setBusy(false)
    }
  }

  /**
   * Copies the invitation link, or admits it could not.
   *
   * This was `await navigator.clipboard.writeText(link)` in a function nothing awaited. On a page
   * that is not `https:` or `localhost` — which `config.ts` supports deliberately — that property
   * does not exist and the call threw into an unhandled rejection: no copy, no "Copied", nothing
   * on screen. The link is a bearer credential shown exactly once, so failing to hand it over
   * without saying so costs the user the invitation itself.
   *
   * The fallback is the field the link is already sitting in: select it, and say which key to
   * press. See `ui/browser.ts` for why there is no `execCommand` fallback behind this.
   */
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
    // Every one of these three used to be an unhandled rejection on failure. The role dropdown is
    // the worst of them: React keeps showing the value the user picked, so a refused change looks
    // exactly like a successful one — the interface asserting somebody has edit rights they were
    // never given.
    await attempt('Could not change that person’s role.', async () => {
      await api.setMemberRole(documentId, userId, role)
    })
    await reload()
  }

  async function remove(userId: string) {
    await attempt('Could not remove that person from the document.', async () => {
      await api.removeMember(documentId, userId)
    })
    await reload()
  }

  async function setVisibility(visibility: 'private' | 'link') {
    await attempt('Could not change who can open this document.', async () => {
      await api.setVisibility(documentId, visibility)
    })
    await reload
  }

  async function revokeInvite(inviteId: string) {
    await attempt('Could not revoke that invitation link — it may still work.',
      async () => {
        await api.revokeInvite(documentId, inviteId)
      })
    await reload()
  }

  return (
    <div className="share-backdrop" onClick={onClose}>
      <div
        className="share-panel"
        role="dialog"
        // `aria-modal` is what tells a screen reader that everything behind the backdrop is
        // unavailable. Without it the dialog is announced as a region of a page the user can still
        // wander into, which is the opposite of what a backdrop means.
        aria-modal="true"
        aria-label="Share this document"
        // The dialog itself takes focus when it opens; see the trap effect above. -1 keeps it out
        // of the Tab order once focus has moved into the controls.
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
                <button className="btn-outline" onClick={() => void createInvite} disabled={busy}>
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
                    // Not phrased as an error, because nothing went wrong that the user has to
                    // understand — this browser simply has no clipboard API on a page that is not
                    // https (see ui/browser.ts). The link is already selected above.
                    <p className="share-hint">
                      This browser will not let a page write to the clipboard here. The link is
                      selected above — copy it with ⌘C or Ctrl+C.
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
                  value={state.visibility}
                  onChange={(event) =>
                    void setVisibility(event.target.value as 'private' | 'link')
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
