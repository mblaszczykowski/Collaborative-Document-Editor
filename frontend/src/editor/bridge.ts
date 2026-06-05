import type { ChangeSet, Text } from '@codemirror/state'
import type { RgaDocument } from '../crdt/document'
import type { Op } from '../crdt/types'

/**
 * Most między CodeMirrorem a CRDT.
 *
 * ADR-0006 ustala granicę: **źródłem prawdy jest CRDT, CodeMirror jest projekcją.**
 * Każda zmiana lokalna idzie drogą `CM → operacja CRDT → scalenie → z powrotem do CM`,
 * nigdy „zmieniamy CM i osobno CRDT". Ten plik jest jedynym miejscem, w którym te dwa
 * modele się spotykają — celowo, żeby rozjazd miał tylko jedno możliwe źródło.
 */

/**
 * Zamienia zestaw zmian CodeMirrora na operacje CRDT.
 *
 * **Zmiany są przetwarzane OD KOŃCA.** Powód: `iterChanges` podaje pozycje w dokumencie
 * *sprzed* zmiany, a każda zastosowana operacja przesuwa wszystko, co jest za nią.
 * Idąc od początku, drugie i kolejne zmiany trafiałyby w przesunięte miejsca — błąd,
 * który przy pojedynczej zmianie (czyli przy zwykłym pisaniu) w ogóle się nie ujawnia
 * i wychodzi dopiero przy wklejeniu z wielokrotnym zaznaczeniem albo przy „zamień wszystko".
 */
export function changesToOps(document: RgaDocument, changes: ChangeSet): Op[] {
  const collected: { fromA: number; toA: number; inserted: string }[] = []
  changes.iterChanges((fromA, toA, _fromB, _toB, inserted: Text) => {
    collected.push({ fromA, toA, inserted: inserted.toString() })
  })

  const ops: Op[] = []
  for (let i = collected.length - 1; i >= 0; i--) {
    const change = collected[i]
    if (change.toA > change.fromA) {
      ops.push(document.delete(change.fromA, change.toA - change.fromA))
    }
    if (change.inserted.length > 0) {
      ops.push(document.insert(change.fromA, change.inserted))
    }
  }
  return ops
}

/**
 * Minimalna zmiana zamieniająca `current` na `target`.
 *
 * Po scaleniu operacji zdalnych mamy nowy tekst CRDT i chcemy przenieść go do CodeMirrora
 * tak, żeby **kursor użytkownika nie skoczył**. Podmiana całego dokumentu jest poprawna,
 * ale ustawiłaby kursor na końcu i zrzuciła zaznaczenie — czyli psułaby pisanie dokładnie
 * wtedy, gdy ktoś inny edytuje ten sam akapit.
 *
 * Wspólny przedrostek i przyrostek wystarczają, bo operacja zdalna to zwykle jedna zwarta
 * zmiana. Przy kilku rozrzuconych zmianach naraz wyliczony zakres jest szerszy niż konieczny —
 * poprawnie, ale mniej oszczędnie.
 *
 * Koszt to O(długość dokumentu) na paczkę operacji zdalnych. **Do zmierzenia w CP8**:
 * przy dokumencie rzędu 100 tys. znaków i intensywnej edycji przez wiele osób to może
 * przestać wystarczać, a wtedy właściwą odpowiedzią jest wyliczanie pozycji wprost
 * z identyfikatorów operacji, a nie porównywanie tekstu.
 */
export function minimalChange(
  current: string,
  target: string,
): { from: number; to: number; insert: string } | null {
  if (current === target) return null

  let prefix = 0
  const maxPrefix = Math.min(current.length, target.length)
  while (prefix < maxPrefix && current[prefix] === target[prefix]) prefix++

  let suffix = 0
  const maxSuffix = Math.min(current.length - prefix, target.length - prefix)
  while (
    suffix < maxSuffix &&
    current[current.length - 1 - suffix] === target[target.length - 1 - suffix]
  ) {
    suffix++
  }

  return {
    from: prefix,
    to: current.length - suffix,
    insert: target.slice(prefix, target.length - suffix),
  }
}
