import { StateEffect, StateField } from '@codemirror/state'
import { Decoration, EditorView, type DecorationSet } from '@codemirror/view'
import type { Span } from '../crdt/marks'

/**
 * Rysowanie formatowania tekstu (CP13).
 *
 * Ta warstwa jest **wyłącznie widokiem**, dokładnie jak reszta integracji z CodeMirrorem
 * (ADR-0006). Nie decyduje, co jest pogrubione — dostaje gotowe odcinki wyliczone przez
 * warstwę CRDT i zamienia je na dekoracje. Gdyby decydowała sama, byłby to trzeci model
 * dokumentu obok CRDT i CodeMirrora, czyli trzecia rzecz do rozjechania się.
 *
 * Odcinki przychodzą w widocznych współrzędnych, przeliczone na ostatnią chwilę przed
 * rysowaniem — z tego samego powodu, dla którego tak działają kursory: dokument mógł się
 * zmienić między wyliczeniem a narysowaniem.
 */

export const setFormatting = StateEffect.define<Span[]>()

/**
 * Klasa CSS na typ znacznika.
 *
 * Nieznany typ dostaje klasę ogólną zamiast być pomijany. Znacznik nadany przez nowszą
 * wersję klienta ma być widoczny jako COŚ — brak wyróżnienia sprawiłby, że użytkownik
 * starszej wersji nie wie, że tekst jest sformatowany, i skasowałby formatowanie,
 * nie wiedząc o jego istnieniu.
 */
function classOf(type: string): string {
  switch (type) {
    case 'bold':
      return 'cm-mark-bold'
    case 'italic':
      return 'cm-mark-italic'
    case 'underline':
      return 'cm-mark-underline'
    case 'strike':
      return 'cm-mark-strike'
    case 'code':
      return 'cm-mark-code'
    case 'link':
      return 'cm-mark-link'
    default:
      return 'cm-mark-unknown'
  }
}

function decorationsFor(spans: Span[], documentLength: number): DecorationSet {
  const ranges = []
  for (const span of spans) {
    // Przycinanie do długości dokumentu: odcinek wyliczony przed ostatnią zmianą mógłby
    // wystawać poza tekst, a CodeMirror rzuca wtedy wyjątkiem przy rysowaniu. To jest
    // ten sam wyścig, przed którym chroni przycinanie pozycji kursorów.
    const from = Math.max(0, Math.min(span.from, documentLength))
    const to = Math.max(0, Math.min(span.to, documentLength))
    if (from >= to) continue

    const classes = [...span.marks.keys()].map(classOf).join(' ')
    const href = span.marks.get('link')
    ranges.push(
      Decoration.mark({
        class: classes,
        // Adres linku jako atrybut, nie jako element <a>: dokument jest edytowalny,
        // a klikalny link wewnątrz pola edycji przechwytywałby kliknięcia zamiast
        // ustawiać kursor. Adres jest widoczny w podpowiedzi.
        attributes: href ? { title: href } : undefined,
      }).range(from, to),
    )
  }
  return Decoration.set(ranges, true)
}

export const formattingField = StateField.define<DecorationSet>({
  create() {
    return Decoration.none
  },

  update(decorations, transaction) {
    // Najpierw przesuwamy istniejące dekoracje wraz ze zmianami w dokumencie. Bez tego
    // formatowanie „migałoby" przy każdym naciśnięciu klawisza: znikało na jedną klatkę
    // i wracało dopiero po przeliczeniu odcinków przez warstwę CRDT.
    let updated = decorations.map(transaction.changes)
    for (const effect of transaction.effects) {
      if (effect.is(setFormatting)) {
        updated = decorationsFor(effect.value, transaction.state.doc.length)
      }
    }
    return updated
  },

  provide: (field) => EditorView.decorations.from(field),
})
