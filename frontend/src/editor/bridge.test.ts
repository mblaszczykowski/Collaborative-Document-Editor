import { ChangeSet, Text } from '@codemirror/state'
import { describe, expect, it } from 'vitest'
import { RgaDocument } from '../crdt/document'
import { changesToOps, minimalChange } from './bridge'

describe('minimalChange', () => {
  it('wstawienie w środku dotyka tylko wstawionego fragmentu', () => {
    expect(minimalChange('abcdef', 'abcXYdef')).toEqual({ from: 3, to: 3, insert: 'XY' })
  })

  it('usunięcie w środku nie rusza otoczenia', () => {
    expect(minimalChange('abcdef', 'abef')).toEqual({ from: 2, to: 4, insert: '' })
  })

  it('identyczny tekst nie generuje zmiany', () => {
    expect(minimalChange('abc', 'abc')).toBeNull()
  })

  it('powtarzające się znaki nie mylą wyliczenia przyrostka', () => {
    // Klasyczna pułapka: naiwne liczenie wspólnego przyrostka potrafi "przejechać"
    // przez przedrostek i wyprodukować ujemny zakres.
    const change = minimalChange('aaa', 'aa')!
    expect(change.to - change.from).toBe(1)
    expect(change.insert).toBe('')
    expect('aaa'.slice(0, change.from) + change.insert + 'aaa'.slice(change.to)).toBe('aa')
  })

  it('zamiana całości gdy nie ma nic wspólnego', () => {
    expect(minimalChange('abc', 'xyz')).toEqual({ from: 0, to: 3, insert: 'xyz' })
  })
})

describe('changesToOps', () => {
  function applyLocal(initial: string, changes: ChangeSet): RgaDocument {
    const document = new RgaDocument(1)
    if (initial.length > 0) document.insert(0, initial)
    changesToOps(document, changes)
    return document
  }

  it('pojedyncze wstawienie', () => {
    const changes = ChangeSet.of([{ from: 3, insert: 'XY' }], 6)
    expect(applyLocal('abcdef', changes).text).toBe('abcXYdef')
  })

  it('pojedyncze usunięcie', () => {
    const changes = ChangeSet.of([{ from: 2, to: 4 }], 6)
    expect(applyLocal('abcdef', changes).text).toBe('abef')
  })

  it('KILKA zmian naraz — wielokrotne zaznaczenie', () => {
    // To jest przypadek, dla którego zmiany muszą być przetwarzane OD KOŃCA.
    // Przy przetwarzaniu od początku druga zmiana trafiłaby w pozycję przesuniętą
    // przez pierwszą — i test dałby "aXbcdYef" albo coś jeszcze gorszego.
    const changes = ChangeSet.of(
      [
        { from: 1, insert: 'X' },
        { from: 4, insert: 'Y' },
      ],
      6,
    )
    expect(applyLocal('abcdef', changes).text).toBe('aXbcdYef')
  })

  it('zamiana zaznaczonego tekstu w kilku miejscach', () => {
    const changes = ChangeSet.of(
      [
        { from: 0, to: 2, insert: 'Z' },
        { from: 4, to: 6, insert: 'W' },
      ],
      6,
    )
    expect(applyLocal('abcdef', changes).text).toBe('ZcdW')
  })

  it('wynik zgadza się z tym, co wyliczył sam CodeMirror', () => {
    // Najmocniejsza forma tego testu: nie sprawdzamy oczekiwanego stringa z głowy,
    // tylko porównujemy z wynikiem, jaki daje własna implementacja CodeMirrora.
    // Rozjazd między nimi to dokładnie ten błąd, który psuje edytor w praktyce.
    const before = 'The quick brown fox'
    const changes = ChangeSet.of(
      [
        { from: 4, to: 9, insert: 'slow' },
        { from: 16, insert: 'es' },
      ],
      before.length,
    )
    const expected = changes.apply(Text.of([before])).toString()
    expect(applyLocal(before, changes).text).toBe(expected)
  })
})
