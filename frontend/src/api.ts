import { API_BASE as BASE } from './config'

export interface AuthOptions {
  providers: string[]
  devLoginEnabled: boolean
}

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
  preview: string
  updatedAt: string
  role?: DocumentState['role']
}

export interface DocumentState {
  id: string
  title: string
  text: string
  headServerSeq: number
  role: 'owner' | 'editor' | 'commenter' | 'viewer'
}

let authToken: string | null = null

export function setAuthToken(token: string | null): void {
  authToken = token
}

function authHeaders(): Record<string, string> {
  return authToken === null ? {} : { Authorization: `Bearer ${authToken}` }
}

let onUnauthorized: (() => void) | null = null

export function setUnauthorizedHandler(handler: (() => void) | null): void {
  onUnauthorized = handler
}

function reportUnauthorized(): void {
  if (authToken === null || onUnauthorized === null) return
  onUnauthorized()
}

export class ApiError extends Error {
  readonly status: number

  constructor(method: string, path: string, status: number, options?: ErrorOptions) {
    super(status === 0 ? `${method} ${path} → no answer`: `${method} ${path} → ${status}`, options)
    this.name = 'ApiError'
    this.status = status
  }
}

async function send<T>(path: string, init: RequestInit | undefined, expectBody: boolean): Promise<T> {
  const method = init?.method ?? 'GET'
  const requestToken = authToken
  let response: Response
  try {
    response = await fetch(`${BASE}${path}`, {
    ...init,
      signal: init?.signal ?? AbortSignal.timeout(15_000),
      headers: { 'Content-Type': 'application/json',...authHeaders(),...(init?.headers ?? {}) },
    })
  } catch (cause) {
    throw new ApiError(method, path, 0, { cause })
  }
  if (!response.ok) {
    if (response.status === 401 && requestToken === authToken) {
      reportUnauthorized()
    }
    throw new ApiError(method, path, response.status)
  }
  return expectBody ? ((await response.json()) as T) : (undefined as T)
}

function request<T>(path: string, init?: RequestInit): Promise<T> {
  return send<T>(path, init, true)
}

function requestNoContent(path: string, init: RequestInit): Promise<void> {
  return send<void>(path, init, false)
}

export interface DiffSegment {
  kind: 'same' | 'added' | 'removed'
  text: string
}

export interface WireSpan {
  from: number
  to: number
  marks: Record<string, string | null>
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
}

export interface CommentThread {
  id: string
  documentId: string
  createdBy: string
  createdAt: string
  resolvedAt: string | null
  quotedText: string
  anchored: boolean
  messages: CommentMessage[]
}

export interface WireId {
  replicaId: number
  lamport: number
}

export interface Suggestion {
  id: string
  documentId: string
  authorId: string
  authorName: string
  authorColor: string
  kind: 'INSERT' | 'DELETE'
  quotedText: string
  createdAt: string
  anchored: boolean
  range: { from: number; to: number } | null
}

export interface AccessState {
  visibility: 'private' | 'link'
  ownerId: string | null
  members: { userId: string; displayName: string; role: DocumentState['role'] }[]
  invites: { id: string; role: DocumentState['role']; expiresAt: string }[]
}

export const api = {
  history: (documentId: string) =>
    request<Version[]>(`/documents/${documentId}/history`),

  textAt: (documentId: string, throughServerSeq: number) =>
    request<{ throughServerSeq: number; text: string; marks?: WireSpan[] }>(
      `/documents/${documentId}/history/at?seq=${throughServerSeq}`,
    ),

  compare: (documentId: string, from: number, to: number) =>
    request<{ fromServerSeq: number; toServerSeq: number; segments: DiffSegment[] }>(
      `/documents/${documentId}/history/diff?from=${from}&to=${to}`,
    ),

  restore: (documentId: string, throughServerSeq: number) =>
    request<{ operationsGenerated: number; text: string }>(
      `/documents/${documentId}/history/restore`,
      { method: 'POST', body: JSON.stringify({ throughServerSeq }) },
    ),

  snapshot: async (
    documentId: string,
  ): Promise<{ bytes: Uint8Array; throughServerSeq: number } | null> => {
    const requestToken = authToken
    const response = await fetch(`${BASE}/documents/${documentId}/snapshot`, {
      headers: authHeaders(),
      signal: AbortSignal.timeout(15_000),
    })
    if (!response.ok) {
      if (response.status === 401 && requestToken === authToken) {
        reportUnauthorized()
      }
      return null
    }
    const throughServerSeq = Number(response.headers.get('X-Through-Server-Seq') ?? '0')
    return { bytes: new Uint8Array(await response.arrayBuffer()), throughServerSeq }
  },

  authOptions: () => request<AuthOptions>('/auth/options'),

  session: (code: string) =>
    request<Session>('/session', { method: 'POST', body: JSON.stringify({ code }) }),

  login: (displayName: string) =>
    request<Session>('/login', { method: 'POST', body: JSON.stringify({ displayName }) }),

  listDocuments: () => request<DocumentSummary[]>('/documents'),

  searchDocuments: (query: string) =>
    request<DocumentSummary[]>(`/documents?q=${encodeURIComponent(query)}`),

  listTrash: () => request<DocumentSummary[]>('/documents/trash'),

  renameDocument: (id: string, title: string) =>
    requestNoContent(`/documents/${id}`, { method: 'PATCH', body: JSON.stringify({ title }) }),

  deleteDocument: (id: string) => requestNoContent(`/documents/${id}`, { method: 'DELETE' }),

  restoreDocument: (id: string) =>
    requestNoContent(`/documents/${id}/restore`, { method: 'POST' }),

  createRecovered: (title: string, text: string, marks?: WireSpan[]) =>
    request<DocumentSummary>('/documents/recovered', {
      method: 'POST',
      body: JSON.stringify({ title, text, marks }),
    }),

  createDocument: (title: string) =>
    request<DocumentSummary>('/documents', { method: 'POST', body: JSON.stringify({ title }) }),

  openDocument: (id: string) => request<DocumentState>(`/documents/${id}`),

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

  access: (documentId: string) => request<AccessState>(`/documents/${documentId}/access`),

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

  redeemInvite: (token: string) =>
    request<{ documentId: string; role: DocumentState['role'] }>('/invites/redeem', {
      method: 'POST',
      body: JSON.stringify({ token }),
    }),
}
