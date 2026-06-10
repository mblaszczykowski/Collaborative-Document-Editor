import { useCallback, useEffect, useState } from 'react'
import { api, type CommentThread } from '../api'
import { formatTime, initials } from './format'

/**
 * Panel komentarzy (CP14).
 *
 * **Wątek odłączony nie znika.** Gdy zakotwiczony tekst zostanie skasowany, kotwica przestaje
 * cokolwiek obejmować — ale rozmowa zostaje, pokazana z zacytowanym fragmentem z chwili
 * założenia. Kasowanie rozmowy w reakcji na edycję tekstu jest zachowaniem, którego nikt
 * się nie spodziewa, a kontekst decyzji jest zwykle jedyną rzeczą, dla której ktoś wraca
 * do komentarzy po miesiącach.
 *
 * **Rozwiązanie też nie jest usunięciem** — wątek schodzi z listy domyślnej i wraca
 * po przełączeniu widoku.
 */
export function CommentsPanel(props: {
  documentId: string
  /** Zaznaczony fragment tekstu; pusty, gdy nic nie jest zaznaczone. */
  selection: string
  /** Rośnie przy każdej zmianie dokumentu — wymusza odświeżenie stanu zakotwiczenia. */
  revision: number
  onAnchor: (threadId: string) => void
  /**
   * Rola nie pozwala komentować (`viewer`).
   *
   * Panel zostaje widoczny — czytelnik ma prawo CZYTAĆ rozmowę, tylko nie dopisywać do niej.
   * Ukrycie panelu odbierałoby mu kontekst, dla którego zwykle się go otwiera.
   */
  readOnly?: boolean
}) {
  const [threads, setThreads] = useState<CommentThread[]>([])
  const [showResolved, setShowResolved] = useState(false)
  const [draft, setDraft] = useState('')
  const [replyTo, setReplyTo] = useState<string | null>(null)
  const [replyDraft, setReplyDraft] = useState('')

  const reload = useCallback(async () => {
    setThreads(await api.listComments(props.documentId, showResolved))
  }, [props.documentId, showResolved])

  useEffect(() => {
    void reload()
  }, [reload, props.revision])

  async function open() {
    if (draft.trim() === '') return
    const created = await api.openThread(props.documentId, props.selection, draft.trim())
    setDraft('')
    // Kotwica powstaje PO wątku, bo potrzebuje jego identyfikatora jako wartości znacznika.
    // Idzie osobną drogą — WebSocketem, jako zwykła operacja CRDT.
    props.onAnchor(created.id)
    await reload()
  }

  async function reply(threadId: string) {
    if (replyDraft.trim() === '') return
    await api.replyToThread(threadId, replyDraft.trim())
    setReplyDraft('')
    setReplyTo(null)
    await reload()
  }

  async function toggleResolved(thread: CommentThread) {
    if (thread.resolvedAt === null) {
      await api.resolveThread(thread.id)
    } else {
      await api.reopenThread(thread.id)
    }
    await reload()
  }

  return (
    <aside className="rightpanel">
      <div className="rp-header">
        Komentarze
        <button className="rp-toggle" onClick={() => setShowResolved((on) => !on)}>
          {showResolved ? 'Ukryj rozwiązane' : 'Pokaż rozwiązane'}
        </button>
      </div>

      <div className="rp-body">
        <div className="comment-new">
          {props.readOnly ? (
            <div className="rp-empty">Twoja rola pozwala czytać komentarze, ale nie dodawać.</div>
          ) : props.selection === '' ? (
            <div className="rp-empty">Zaznacz fragment tekstu, żeby go skomentować.</div>
          ) : (
            <>
              <div className="comment-quote">„{props.selection}"</div>
              <textarea
                className="comment-draft"
                value={draft}
                placeholder="Twój komentarz"
                onChange={(event) => setDraft(event.target.value)}
              />
              <button className="comment-submit" onClick={() => void open()} disabled={draft.trim() === ''}>
                Skomentuj
              </button>
            </>
          )}
        </div>

        {threads.length === 0 && <div className="rp-empty">Brak komentarzy.</div>}

        {threads.map((thread) => (
          <div
            key={thread.id}
            className={`comment-thread${thread.resolvedAt !== null ? ' resolved' : ''}${
              thread.anchored ? '' : ' detached'
            }`}
          >
            <div className="comment-quote">
              „{thread.quotedText}"
              {!thread.anchored && (
                <span className="comment-detached-mark" title="Zakotwiczony tekst został usunięty">
                  odłączony
                </span>
              )}
            </div>

            {thread.messages.map((message) => (
              <div key={message.id} className="comment-message">
                <span className="comment-avatar" style={{ background: message.authorColor }}>
                  {initials(message.authorName)}
                </span>
                <div className="comment-body">
                  <div className="comment-meta">
                    {message.authorName}
                    <span className="h-time">{formatTime(message.createdAt)}</span>
                  </div>
                  {message.body}
                </div>
              </div>
            ))}

            <div className="comment-actions">
              {props.readOnly ? null : replyTo === thread.id ? (
                <>
                  <input
                    className="comment-reply"
                    value={replyDraft}
                    autoFocus
                    placeholder="Odpowiedz"
                    onChange={(event) => setReplyDraft(event.target.value)}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter') void reply(thread.id)
                      if (event.key === 'Escape') setReplyTo(null)
                    }}
                  />
                </>
              ) : (
                <button className="comment-action" onClick={() => setReplyTo(thread.id)}>
                  Odpowiedz
                </button>
              )}
              {!props.readOnly && (
                <button className="comment-action" onClick={() => void toggleResolved(thread)}>
                  {thread.resolvedAt === null ? 'Rozwiąż' : 'Otwórz ponownie'}
                </button>
              )}
            </div>
          </div>
        ))}
      </div>
    </aside>
  )
}
