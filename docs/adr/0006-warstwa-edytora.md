# ADR-0006 — Warstwa edytora: CodeMirror 6 jako widok, model dokumentu własny

- **Status:** Zaakceptowany
- **Data:** 2026-08-14
- **Dotyczy checkpointów:** CP4

---

## Kontekst

Frontend musi: renderować tekst, przyjmować edycję z klawiatury, rysować **kursory i zaznaczenia
innych użytkowników** w tekście, i zamieniać lokalne edycje na operacje CRDT (oraz odwrotnie).

`CLAUDE.md` zabrania używania gotowych **bibliotek CRDT** jako czarnej skrzynki. `idea.md`
jednocześnie explicite dopuszcza bibliotekę jako **warstwę UI** („np. na bazie ContentEditable
lub biblioteki typu Slate.js/ProseMirror jako warstwa UI, z własną logiką CRDT pod spodem").

Granica jest więc jasna i warto ją nazwać wprost: **model dokumentu i algorytm scalania są
nasze; renderowanie tekstu i obsługa zdarzeń wejściowych przeglądarki nie muszą być.**
Trudność projektu ma leżeć w spójności rozproszonej, nie w obsłudze IME.

---

## Rozważane opcje

### Opcja A — `<textarea>`

**Za:** trywialne, zero zależności, zerowa liczba niespodzianek.
**Przeciw:** **nie da się narysować niczego wewnątrz tekstu.** Zdalne kursory, zaznaczenia
i kolory autorów są niemożliwe. To wycina najbardziej efektowną i najczęściej wymienianą
funkcję z `idea.md` — i akurat tę, która sprzedaje demo. Odrzucone.

### Opcja B — Własny edytor na `contentEditable`

**Za:** pełna kontrola, brak zależności, „napisane od zera" brzmi mocno.
**Przeciw:** `contentEditable` to jedna z najbardziej niespójnych między przeglądarkami części
DOM-u. Realna lista rzeczy do rozwiązania samodzielnie:
- **IME / composition events** — pisanie po chińsku, japońsku, koreańsku, ale też autokorekta
  i podpowiedzi na klawiaturach mobilnych. `compositionstart`/`compositionupdate`/`compositionend`
  vs zdarzenia `input` to źródło błędów, na których wykłada się większość naiwnych edytorów.
- **Natywne undo/redo przeglądarki** — działa na DOM, nie na naszym modelu; trzeba je przechwycić
  i zastąpić, w tym `Ctrl+Z` z menu kontekstowego.
- Wklejanie (HTML → tekst), przeciąganie tekstu myszą, autokorekta iOS, różnice w normalizacji
  węzłów tekstowych, zachowanie kursora przy granicach węzłów.

Koszt: realistycznie tydzień pracy, z czego **nic nie demonstruje zrozumienia CRDT** — to jest
walka z DOM-em, nie z algorytmem. Odrzucone jako zły podział budżetu w projekcie o skończonym
czasie, którego celem jest głębia w problemie rdzeniowym.

### Opcja C — ProseMirror / Slate

**Za:** dojrzałe, dobrze rozwiązują `contentEditable`.
**Przeciw:** oba są zbudowane wokół **modelu dokumentu drzewiastego** (rich text, schema,
zagnieżdżone węzły). Nasz dokument to płaska sekwencja znaków (świadome ograniczenie zakresu
z `idea.md`). Dostajemy więc całą złożoność modelu drzewiastego, żeby użyć jednego typu węzła,
i musimy mapować nasz płaski CRDT na ich pozycje w drzewie — dodatkowa warstwa tłumaczenia
bez korzyści.

### Opcja D — CodeMirror 6 jako czysta warstwa widoku

CM6 jest zbudowany wokół **płaskiego dokumentu tekstowego** — dokładnie nasz model.
Kluczowe własności:
- **Transakcje z `ChangeSet`** — każda edycja użytkownika przychodzi jako jawny, opisany
  zestaw zmian (`fromA/toA/fromB/toB` + wstawiony tekst), a nie jako różnica DOM-u do odgadnięcia.
  Mapowanie na operacje CRDT jest bezpośrednie.
- **Rozwiązane IME, undo, wklejanie, mobile** — przetestowane w produkcji przez lata.
- **`Decoration` i widgety** — natywny mechanizm rysowania kursorów i zaznaczeń innych
  użytkowników bez dotykania DOM-u ręcznie.
- **`StateField` / `StateEffect`** — miejsce na trzymanie stanu presence poza treścią dokumentu.
- Tree-shaking, modularna budowa — bierzemy tylko potrzebne moduły.

**Przeciw:**
- Zależność zewnętrzna w ścieżce krytycznej UI.
- CM6 ma **własny** model dokumentu (`Text`) i własną historię — dwa modele obok siebie
  wymagają dyscypliny, żeby nie rozjechać się co do tego, który jest źródłem prawdy.
- Krzywa uczenia się architektury CM6 (state/view, facets, transakcje) jest niepłaska.

---

## Decyzja

**Opcja D — CodeMirror 6 wyłącznie jako warstwa widoku.**

### Granica odpowiedzialności — spisana, bo bez tego się rozmyje

| Odpowiedzialność | Właściciel |
|---|---|
| Struktura dokumentu, identyfikatory znaków, tombstone'y | **nasz kod** (`crdt-ts`) |
| Scalanie operacji, zbieżność, version vectory | **nasz kod** |
| Bufor offline, resynchronizacja | **nasz kod** |
| Pozycje kursorów zdalnych (jako identyfikatory CRDT) | **nasz kod** |
| Renderowanie tekstu, zdarzenia klawiatury, IME, wklejanie | CodeMirror |
| Rysowanie dekoracji w miejscu wskazanym przez nasz kod | CodeMirror |

**Źródłem prawdy jest CRDT, nie `EditorState` CodeMirrora.** CM6 jest projekcją: każda
zmiana lokalna idzie `CM6 → operacja CRDT → merge → nowy stan → z powrotem do CM6`, nigdy
„zmieniamy CM6 i osobno CRDT". Historia CM6 (`history()`) jest **wyłączona** — undo musi
działać na operacjach CRDT (cofać własne operacje użytkownika), a nie na tekście, bo inaczej
cofnięcie zdejmowałoby zmiany innych osób.

### Kursory zdalne trzymamy jako identyfikator CRDT, nie offset

To nie jest detal implementacyjny, tylko rozstrzygnięcie klasycznego błędu. Gdyby pozycja
kursora Bartka była offsetem (`kolumna 42`), to wstawienie przez Anię znaku przed tą pozycją
przesunęłoby tekst, ale nie kursor — i kursor Bartka wskazywałby cudzy znak. Trzymając
pozycję jako **identyfikator węzła CRDT**, kursor przesuwa się razem z tekstem automatycznie,
bo jest przyczepiony do znaku, a nie do miejsca.

Ten sam problem dotyczy zaznaczeń (para identyfikatorów) i lokalnego kursora użytkownika
podczas przychodzącej edycji zdalnej.

---

## Konsekwencje

### Pozytywne
- Cały budżet czasowy trafia do problemu rdzeniowego (CRDT, spójność, obserwowalność),
  a nie do walki z `contentEditable`.
- `ChangeSet` CM6 mapuje się na operacje CRDT niemal jeden do jednego — mniej miejsca na błąd
  niż przy odgadywaniu zmian z mutacji DOM.
- Zdalne kursory i zaznaczenia dostają wsparcie z pierwszej ręki (`Decoration`), więc
  najlepiej wyglądająca część demo jest jednocześnie najtańsza w wykonaniu.
- Edycja na urządzeniu mobilnym i z IME działa, choć nie jest przedmiotem tego projektu.

### Negatywne
- **Dwa modele dokumentu obok siebie** (CRDT i `EditorState`). Każdy rozjazd między nimi
  to trudny do wyśledzenia błąd typu „tekst na ekranie nie zgadza się ze stanem". Mitygacja:
  invariant sprawdzany w trybie deweloperskim po każdej transakcji (`crdtDoc.text() === cmState.doc.toString()`),
  z głośnym błędem przy niezgodności. To ma być asercja, nie nadzieja.
- **Zależność zewnętrzna** — jeśli CM6 zmieni API transakcji, warstwa mostkująca wymaga pracy.
  Ograniczone do jednego modułu (`frontend/src/editor/`), żeby zmiana nie promieniowała.
- **Wyłączenie `history()` CM6** oznacza, że undo/redo musimy zbudować sami na operacjach
  CRDT. To jest nietrywialne (undo w CRDT to nowa operacja odwracająca, nie usunięcie starej)
  i jest **realnym ryzykiem zakresu w CP4** — jeśli zabraknie czasu, undo zostaje ograniczone
  do cofania własnych operacji z bieżącej sesji, co trafi do „co inaczej w produkcji".
- Krzywa uczenia CM6 — koszt jednorazowy, ale niezerowy.

### Do zweryfikowania w CP4
- Czy `ChangeSet` faktycznie da się mapować bez przypadków brzegowych przy operacjach
  łączonych (wielokrotne zaznaczenia, zamiana zaznaczonego tekstu jedną transakcją).
- Czy invariant „tekst CM6 == tekst CRDT" utrzymuje się przy jednoczesnej edycji lokalnej
  i przychodzącej zdalnej (to jest test, nie założenie).
- Czy dekoracje kursorów wytrzymują 20+ jednoczesnych użytkowników bez zauważalnego
  spadku płynności — jeśli nie, mitygacją jest ograniczenie liczby rysowanych kursorów.
