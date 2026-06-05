/**
 * Bliźniacza implementacja RGA po stronie klienta.
 *
 * Istnieje, bo klient musi edytować offline (ADR-0001) — a to znaczy, że musi umieć
 * scalać samodzielnie, bez serwera. Jest to świadomie przyjęty dług: jeden algorytm,
 * dwie implementacje, dwie okazje do błędu.
 *
 * Zabezpieczeniem przed rozjazdem jest korpus wektorów konformancyjnych w `conformance/`,
 * generowany przez testy w Javie i odtwarzany przez `conformance.test.ts`. CI uruchamia
 * obie strony, więc rozjazd nie może przejść po cichu — ale nadal PODWAJA koszt każdej
 * zmiany w algorytmie.
 *
 * Kod jest celowo utrzymany blisko struktury wersji w Javie (te same nazwy, ta sama
 * kolejność metod), żeby porównanie obu przy zmianie było mechaniczne, a nie detektywistyczne.
 */

/**
 * Identyfikator znaku.
 *
 * `lamport` to wartość zegara Lamporta, nie licznik sekwencyjny repliki — patrz
 * `docs/crdt-design.md` sekcja 1. Praktyczna konsekwencja: NIE jest ciągły w obrębie
 * repliki, bo skacze przy odbiorze operacji zdalnych.
 */
export interface Id {
  readonly replicaId: number
  readonly lamport: number
}

export function id(replicaId: number, lamport: number): Id {
  return { replicaId, lamport }
}

export function idPlus(base: Id, offset: number): Id {
  return { replicaId: base.replicaId, lamport: base.lamport + offset }
}

export function idEquals(a: Id | null, b: Id | null): boolean {
  if (a === null || b === null) return a === b
  return a.replicaId === b.replicaId && a.lamport === b.lamport
}

/**
 * Porządek totalny: najpierw lamport, potem replicaId jako rozstrzygnięcie remisu.
 *
 * Remis jest częsty — dwie repliki działające współbieżnie na tym samym stanie wygenerują
 * ten sam lamport. To `replicaId` czyni porządek totalnym bez potrzeby arbitra, co jest
 * całą istotą wyboru z ADR-0001.
 */
export function compareIds(a: Id, b: Id): number {
  if (a.lamport !== b.lamport) return a.lamport - b.lamport
  return a.replicaId - b.replicaId
}

export function idToString(value: Id): string {
  return `${value.replicaId}@${value.lamport}`
}

/** Ciągły zakres identyfikatorów jednej repliki: [start, start+length). */
export interface Range {
  readonly start: Id
  readonly length: number
}

export interface InsertOp {
  readonly kind: 'insert'
  readonly id: Id
  /** null oznacza wstawienie na początku dokumentu. */
  readonly originLeft: Id | null
  readonly text: string
}

export interface DeleteOp {
  readonly kind: 'delete'
  readonly id: Id
  readonly targets: readonly Range[]
}

export type Op = InsertOp | DeleteOp

/** Ile wartości zegara zajmuje operacja: wstawienie n znaków zajmuje n, usunięcie 1. */
export function opSpan(op: Op): number {
  return op.kind === 'insert' ? op.text.length : 1
}

export function opMaxLamport(op: Op): number {
  return op.id.lamport + opSpan(op) - 1
}

/** Co się stało z zastosowaną operacją — odpowiednik RgaDocument.ApplyResult w Javie. */
export type ApplyResult = 'applied' | 'duplicate' | 'buffered'
