/**
 * Klient REST — rzeczy, które nie muszą być w czasie rzeczywistym.
 *
 * Podział z serwerem jest celowy (patrz DocumentRestController): kanałem WebSocket idą
 * wyłącznie zmiany przyrostowe, a stan początkowy pobieramy zwykłym HTTP. Dzięki temu
 * otwarcie dużego dokumentu nie przepycha kilkuset kilobajtów przez kanał zoptymalizowany
 * pod komunikaty rzędu stu bajtów (ADR-0002 ustala limit 64 KiB na ramkę).
 */

const BASE = `http://${window.location.hostname}:8080/api`

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
   * Pierwsze znaki treści, materializowane po stronie serwera przy snapshotowaniu.
   *
   * Pusty, dopóki dokument nie doczekał się pierwszego snapshotu — i to jest w porządku:
   * alternatywą byłoby ładowanie repliki CRDT dla każdej pozycji listy, czyli koszt rosnący
   * z liczbą dokumentów razy ich rozmiar.
   */
  preview: string
  updatedAt: string
}

export interface DocumentState {
  id: string
  title: string
  text: string
  headServerSeq: number
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${BASE}${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
  })
  if (!response.ok) {
    throw new Error(`${init?.method ?? 'GET'} ${path} → ${response.status}`)
  }
  return (await response.json()) as T
}

/**
 * Żądanie bez treści w odpowiedzi (204).
 *
 * Osobna funkcja, bo `request` bezwarunkowo woła `response.json()` — a to na pustej
 * odpowiedzi rzuca wyjątkiem parsowania. Objawiałoby się jako „zmiana nazwy nie działa",
 * mimo że serwer wykonał ją poprawnie.
 */
async function requestNoContent(path: string, init: RequestInit): Promise<void> {
  const response = await fetch(`${BASE}${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...(init.headers ?? {}) },
  })
  if (!response.ok) {
    throw new Error(`${init.method ?? 'GET'} ${path} → ${response.status}`)
  }
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

export const api = {
  history: (documentId: string) =>
    request<Version[]>(`/documents/${documentId}/history`),

  textAt: (documentId: string, throughServerSeq: number) =>
    request<{ throughServerSeq: number; text: string }>(
      `/documents/${documentId}/history/at?seq=${throughServerSeq}`,
    ),

  /**
   * Przywraca stan z przeszłości.
   *
   * Nie ma tu nic do zrobienia po stronie klienta poza wywołaniem — przywrócenie wraca
   * do wszystkich podłączonych przeglądarek zwykłym kanałem operacji, bo jest zwykłą
   * zmianą (ADR-0003 pkt 6). Ten sam mechanizm, ten sam kod, żadnej ścieżki specjalnej.
   */
  restore: (documentId: string, throughServerSeq: number) =>
    request<{ operationsGenerated: number; text: string }>(
      `/documents/${documentId}/history/restore`,
      { method: 'POST', body: JSON.stringify({ throughServerSeq }) },
    ),

  login: (displayName: string) =>
    request<Session>('/login', { method: 'POST', body: JSON.stringify({ displayName }) }),

  listDocuments: () => request<DocumentSummary[]>('/documents'),

  /**
   * Wyszukiwanie po tytule i treści.
   *
   * Po stronie serwera to ten sam endpoint co lista — z punktu widzenia interfejsu
   * „pokaż dokumenty" i „pokaż dokumenty pasujące do X" to jedna rzecz, różniąca się
   * zawężeniem. Dwa osobne wejścia znaczyłyby dwa miejsca do utrzymania tej samej
   * kolejności i tego samego limitu.
   */
  searchDocuments: (query: string) =>
    request<DocumentSummary[]>(`/documents?q=${encodeURIComponent(query)}`),

  listTrash: () => request<DocumentSummary[]>('/documents/trash'),

  renameDocument: (id: string, title: string) =>
    requestNoContent(`/documents/${id}`, { method: 'PATCH', body: JSON.stringify({ title }) }),

  /** Chowa dokument. Log operacji zostaje nietknięty — patrz DocumentCatalog. */
  deleteDocument: (id: string) => requestNoContent(`/documents/${id}`, { method: 'DELETE' }),

  restoreDocument: (id: string) =>
    requestNoContent(`/documents/${id}/restore`, { method: 'POST' }),

  /** Ratunkowy zapis lokalnej wersji po odmowie scalenia (ADR-0005). */
  createRecovered: (title: string, text: string) =>
    request<DocumentSummary>('/documents/recovered', {
      method: 'POST',
      body: JSON.stringify({ title, text }),
    }),

  createDocument: (title: string) =>
    request<DocumentSummary>('/documents', { method: 'POST', body: JSON.stringify({ title }) }),

  openDocument: (id: string) => request<DocumentState>(`/documents/${id}`),
}
