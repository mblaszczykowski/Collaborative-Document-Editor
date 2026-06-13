import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { markExpandsAtEnd } from './types'

/**
 * Bliźniak `MarkTypesConformanceTest.java` — obie strony czytają TEN SAM plik.
 *
 * Korpus wektorów tego nie złapie: wektory zawierają operacje już utworzone, z konkretnymi
 * stronami kotwic w środku, a tablica typów jest używana przy TWORZENIU operacji. Zmiana
 * jednej strony przechodzi przez cały korpus na zielono i objawia się dopiero u użytkownika
 * jako „u mnie pogrubienie rośnie przy pisaniu, u ciebie nie".
 *
 * ADR-0010 twierdził, że taki test istnieje po obu stronach. Przy przeglądzie okazało się,
 * że nie istniał po żadnej — dokumentacja opisywała zabezpieczenie, którego nie było.
 */

interface MarkTypeTable {
  expandsAtEnd: string[]
  doesNotExpandAtEnd: string[]
}

const TABLE = JSON.parse(
  readFileSync(
    join(__dirname, '..', '..', '..', 'conformance', 'schema', 'mark-types.json'),
    'utf8',
  ),
) as MarkTypeTable

describe('tablica typów znaczników', () => {
  it('zgadza się ze wspólnym źródłem, z którego korzysta też serwer', () => {
    // Pusta lista przepuściłaby każdą regresję — pętla po zerze elementów zawsze przechodzi.
    expect(TABLE.expandsAtEnd.length).toBeGreaterThan(0)
    expect(TABLE.doesNotExpandAtEnd.length).toBeGreaterThan(0)

    for (const type of TABLE.expandsAtEnd) {
      expect(markExpandsAtEnd(type), `${type} nie rośnie w kliencie, a rośnie w Javie`).toBe(true)
    }
    for (const type of TABLE.doesNotExpandAtEnd) {
      expect(markExpandsAtEnd(type), `${type} rośnie w kliencie, a nie rośnie w Javie`).toBe(false)
    }
  })

  it('nieznany typ nie rozszerza się', () => {
    // Wybór ostrożny, ten sam po obu stronach: znacznik rozszerzający się niespodziewanie
    // zmienia wygląd tekstu, którego użytkownik nie formatował.
    expect(markExpandsAtEnd('typ-z-przyszłej-wersji')).toBe(false)
  })
})
