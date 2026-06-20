/**
 * The REST client — the things that need not be real time.
 *
 * The split with the server is deliberate (see DocumentRestController): the WebSocket channel
 * carries only incremental changes, and the initial state is fetched over ordinary HTTP. That way,
 * opening a large document does not push several hundred kilobytes through a channel optimised for
 * messages of about a hundred bytes (ADR-0002 sets a 64 KiB per-frame limit).
 */

import { API_BASE as BASE } from './config'

export interface Session {
  token: string
  userId: string
  displayName: string
  color: string
}

export interface DocumentSummary {
  id: string
  title: string
  workspaceId: string | null
  /**
   * The first characters of the content, materialised on the server at snapshot time.
   *
   * Empty until the document has had its first snapshot — and that is fine: the alternative would
   * be loading a CRDT replica per list entry, that is, a cost growing with the number of documents
   * times their size.
   */
  preview: string
  updatedAt: string
}

export interface DocumentState {
  id: string
  title: string
  text: string
  headServerSeq: number
  /**
   * The caller's role in this document: `owner` | `editor` | `commenter` | `viewer`.
   *
   * Not used for authorization — that lives on the server and stays there entirely. It exists so
   * the interface does not ALLOW a gesture that will be refused anyway: a reader who does not know
   * their role types, sees their own text, and the server rejects it. Their replica never agrees
   * with the rest again, and nothing tells them so.
   */
  role: 'owner' | 'editor' | 'commenter' | 'viewer'
}

/**
 * The current session's token, attached to every request.
 *
 * Held in the module rather than passed to every call: REST is auxiliary to the WebSocket in this
 * application, so threading the token through every call would clutter them with a detail that
 * never changes during a session.
 *
 * Before CP12 the REST endpoints were UNAUTHENTICATED — `GET /api/documents/{id}` returned the
 * content of any document to anyone who knew the identifier. WebSocket authorization without this
 * header would be decoration, because the same data left through one GET.
 */
let authToken: string | null = null

export function setAuthToken(token: string | null): void {
  authToken = token
}

function authHeaders(): Record<string, string> {
  return authToken === null ? {} : { Authorization: `Bearer ${authToken}` }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${BASE}${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...authHeaders(), ...(init?.headers ?? {}) },
  })
  if (!response.ok) {
    throw new Error(`${init?.method ?? 'GET'} ${path} → ${response.status}`)
  }
  return (await response.json()) as T
}

/**
 * A request with no response body (204).
 *
 * A separate function, because `request` calls `response.json()` unconditionally — and on an empty
 * response that throws a parse error. It would manifest as "renaming does not work" even though the
 * server had done it correctly.
 */
async function requestNoContent(path: string, init: RequestInit): Promise<void> {
  const response = await fetch(`${BASE}${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...authHeaders(), ...(init.headers ?? {}) },
  })
  if (!response.ok) {
    throw new Error(`${init.method ?? 'GET'} ${path} → ${response.status}`)
  }
}

/** One run of text in a version comparison (CP22). */
export interface DiffSegment {
  kind: 'same' | 'added' | 'removed'
  text: string
}

/** A range with uniform formatting — the same shape as `Span` in the CRDT layer. */
export interface Span {
  from: number
  to: number
  marks: Record<string, string>
}

export interface Version {
  throughServerSeq: number
  userId: string
  displayName: string
  color: string
  at: string
  operationCount: number
  charactersInserted: number
  charactersDeleted: number
}

export interface CommentMessage {
  id: string
  authorId: string
  authorName: string
  authorColor: string
  body: string
  createdAt: string
  editedAt: string | null
}

export interface CommentThread {
  id: string
  documentId: string
  createdBy: string
  createdAt: string
  resolvedAt: string | null
  /** The text the thread was about when it was opened — all that remains once it detaches. */
  quotedText: string
  /** Whether the anchored text still exists. Computed from the CRDT layer, not from the database. */
  anchored: boolean
  messages: CommentMessage[]
}

/** A character identifier on the wire — the same shape the presence frames use. */
export interface WireId {
  replicaId: number
  lamport: number
}

/** A proposed change (CP23). */
export interface Suggestion {
  id: string
  documentId: string
  authorId: string
  authorName: string
  authorColor: string
  kind: 'INSERT' | 'DELETE'
  /** What was proposed, as of when it was proposed — survives the anchored text. */
  quotedText: string
  createdAt: string
  /** Whether the proposal still has a place in the document. Computed from the CRDT layer. */
  anchored: boolean
  range: { from: number; to: number } | null
}

/** Who has access to a document — the owner's view (CP21). */
export interface AccessState {
  visibility: 'private' | 'link'
  ownerId: string | null
  members: { userId: string; displayName: string; role: DocumentState['role'] }[]
  invites: { id: string; role: DocumentState['role']; expiresAt: string }[]
}

export const api = {
  history: (documentId: string) =>
    request<Version[]>(`/documents/${documentId}/history`),

  /**
   * A past state of the document — text TOGETHER WITH its marks (CP22).
   *
   * Until CP22 only the text arrived, so a version whose sole change was formatting looked
   * identical to the one before it in the preview. The timeline said "3 operations" and the
   * preview showed no difference at all — not a bug, but the CP5 history layer, which nobody
   * revisited after CP13.
   */
  textAt: (documentId: string, throughServerSeq: number) =>
    request<{ throughServerSeq: number; text: string; marks: Span[] }>(
      `/documents/${documentId}/history/at?seq=${throughServerSeq}`,
    ),

  /**
   * What changed between two versions — computed from character IDENTITY, not from the text.
   *
   * Every character carries `(replicaId, lamport)` for the whole life of the document, because
   * that is what merging is built on. Two states of one document are the same log stopped at
   * two points, so characters present in both appear in the same order — the comparison is a
   * single pass with no heuristics. And it answers a question a text diff cannot: deleting a
   * word and retyping it identically is a change.
   */
  compare: (documentId: string, from: number, to: number) =>
    request<{ fromServerSeq: number; toServerSeq: number; segments: DiffSegment[] }>(
      `/documents/${documentId}/history/diff?from=${from}&to=${to}`,
    ),

  /**
   * Restores a past state.
   *
   * There is nothing for the client to do here beyond the call — the restore comes back to every
   * connected browser over the ordinary operation channel, because it is an ordinary change
   * (ADR-0003, point 6). The same mechanism, the same code, no special path.
   */
  restore: (documentId: string, throughServerSeq: number) =>
    request<{ operationsGenerated: number; text: string }>(
      `/documents/${documentId}/history/restore`,
      { method: 'POST', body: JSON.stringify({ throughServerSeq }) },
    ),

  /**
   * The document's state as a CRDT snapshot (CP24).
   *
   * Bytes rather than JSON, and a header rather than a field, because the whole point is how
   * much crosses the wire — see DocumentSnapshotController. A failure here is not an error to
   * report: a snapshot is a cache, so the caller falls back to replaying the log, which is
   * slower and always correct (ADR-0003).
   */
  snapshot: async (
    documentId: string,
  ): Promise<{ bytes: Uint8Array; throughServerSeq: number } | null> => {
    const response = await fetch(`${BASE}/documents/${documentId}/snapshot`, {
      headers: authHeaders(),
    })
    if (!response.ok) {
      return null
    }
    const throughServerSeq = Number(response.headers.get('X-Through-Server-Seq') ?? '0')
    return { bytes: new Uint8Array(await response.arrayBuffer()), throughServerSeq }
  },

  login: (displayName: string) =>
    request<Session>('/login', { method: 'POST', body: JSON.stringify({ displayName }) }),

  listDocuments: () => request<DocumentSummary[]>('/documents'),

  /**
   * Search over title and content.
   *
   * On the server this is the same endpoint as the list — from the interface's point of view "show
   * documents" and "show documents matching X" are one thing differing by a filter. Two separate
   * entry points would mean two places maintaining the same ordering and the same limit.
   */
  searchDocuments: (query: string) =>
    request<DocumentSummary[]>(`/documents?q=${encodeURIComponent(query)}`),

  listTrash: () => request<DocumentSummary[]>('/documents/trash'),

  renameDocument: (id: string, title: string) =>
    requestNoContent(`/documents/${id}`, { method: 'PATCH', body: JSON.stringify({ title }) }),

  /** Hides a document. The operation log is left untouched — see DocumentCatalog. */
  deleteDocument: (id: string) => requestNoContent(`/documents/${id}`, { method: 'DELETE' }),

  restoreDocument: (id: string) =>
    requestNoContent(`/documents/${id}/restore`, { method: 'POST' }),

  /** The rescue save of a local version after a refused merge (ADR-0005). */
  createRecovered: (title: string, text: string) =>
    request<DocumentSummary>('/documents/recovered', {
      method: 'POST',
      body: JSON.stringify({ title, text }),
    }),

  createDocument: (title: string) =>
    request<DocumentSummary>('/documents', { method: 'POST', body: JSON.stringify({ title }) }),

  openDocument: (id: string) => request<DocumentState>(`/documents/${id}`),

  // --- comments (CP14) ---
  //
  // REST, not WebSocket: a comment is created once, whole, and nobody expects it to appear for
  // others in the same frame as a keystroke. The ANCHOR goes over the WebSocket, because it is an
  // ordinary CRDT mark — see CommentController.

  listComments: (documentId: string, includeResolved = false) =>
    request<CommentThread[]>(
      `/documents/${documentId}/comments?resolved=${includeResolved}`,
    ),

  openThread: (documentId: string, quotedText: string, body: string, clientId?: string) =>
    request<CommentThread>(`/documents/${documentId}/comments`, {
      method: 'POST',
      body: JSON.stringify({ quotedText, body, clientId }),
    }),

  replyToThread: (threadId: string, body: string, clientId?: string) =>
    requestNoContent(`/comments/${threadId}/messages`, {
      method: 'POST',
      body: JSON.stringify({ body, clientId }),
    }),

  resolveThread: (threadId: string) =>
    requestNoContent(`/comments/${threadId}/resolve`, { method: 'POST' }),

  reopenThread: (threadId: string) =>
    requestNoContent(`/comments/${threadId}/reopen`, { method: 'POST' }),

  // --- suggestions (CP23) ---
  //
  // A commenter cannot send an Insert operation — that is what the role means — so a proposal
  // is asked for over REST and the SERVER replica generates the operations, exactly as a
  // history restore does. Positions travel as character identifiers, like cursors and marks:
  // an offset is stale the moment somebody types before it.

  listSuggestions: (documentId: string) =>
    request<Suggestion[]>(`/documents/${documentId}/suggestions`),

  proposeInsertion: (documentId: string, after: WireId | null, text: string) =>
    request<{ id: string }>(`/documents/${documentId}/suggestions`, {
      method: 'POST',
      body: JSON.stringify({ kind: 'insert', text, after }),
    }),

  proposeDeletion: (documentId: string, from: WireId, to: WireId) =>
    request<{ id: string }>(`/documents/${documentId}/suggestions`, {
      method: 'POST',
      body: JSON.stringify({ kind: 'delete', from, to }),
    }),

  acceptSuggestion: (documentId: string, suggestionId: string) =>
    requestNoContent(`/documents/${documentId}/suggestions/${suggestionId}/accept`, {
      method: 'POST',
    }),

  rejectSuggestion: (documentId: string, suggestionId: string) =>
    requestNoContent(`/documents/${documentId}/suggestions/${suggestionId}/reject`, {
      method: 'POST',
    }),

  // --- access (CP21) ---
  //
  // Until CP21 these calls did not exist, and there was nothing to call: `grant`/`revoke`
  // lived on the server for the tests only. Inviting goes by LINK rather than by user name,
  // because passwordless login mints a new identifier on every visit — there is nothing to
  // type into a "share with…" box (ADR-0014).

  access: (documentId: string) => request<AccessState>(`/documents/${documentId}/access`),

  /**
   * The caller's role and nothing else.
   *
   * A separate call, because `openDocument` also carries the ENTIRE text — and after an
   * `ACCESS_CHANGED` message we need one word, not a hundred thousand characters.
   */
  myRole: (documentId: string) =>
    request<{ role: DocumentState['role'] }>(`/documents/${documentId}/access/me`),

  setVisibility: (documentId: string, visibility: 'private' | 'link') =>
    requestNoContent(`/documents/${documentId}/access/visibility`, {
      method: 'PUT',
      body: JSON.stringify({ visibility }),
    }),

  setMemberRole: (documentId: string, userId: string, role: DocumentState['role']) =>
    requestNoContent(`/documents/${documentId}/access/members/${userId}`, {
      method: 'PUT',
      body: JSON.stringify({ role }),
    }),

  removeMember: (documentId: string, userId: string) =>
    requestNoContent(`/documents/${documentId}/access/members/${userId}`, { method: 'DELETE' }),

  createInvite: (documentId: string, role: DocumentState['role']) =>
    request<{ token: string; id: string; role: string; expiresAt: string }>(
      `/documents/${documentId}/access/invites`,
      { method: 'POST', body: JSON.stringify({ role }) },
    ),

  revokeInvite: (documentId: string, inviteId: string) =>
    requestNoContent(`/documents/${documentId}/access/invites/${inviteId}`, { method: 'DELETE' }),

  /** Exchanges an invitation token for a role. The document comes FROM THE TOKEN, not the URL. */
  redeemInvite: (token: string) =>
    request<{ documentId: string; role: DocumentState['role'] }>('/invites/redeem', {
      method: 'POST',
      body: JSON.stringify({ token }),
    }),
}
