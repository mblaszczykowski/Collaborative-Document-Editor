# ADR-0007 — Zakres UI: przyjmujemy język wizualny mockupu, odrzucamy jego zakres funkcjonalny

- **Status:** **Zastąpiony przez [ADR-0008](0008-rozszerzenie-zakresu.md)** w części dotyczącej
  zakresu funkcjonalnego. Decyzje o języku wizualnym i o ukryciu diagnostyki pozostają w mocy.
  Nie kasuję tego dokumentu: odrzucenie zakresu było słuszne dla v1 i przestało być słuszne
  dopiero wtedy, gdy rdzeń był gotowy — a to jest informacja, nie wstyd., **zrewidowany** (patrz sekcja na końcu)
- **Data:** 2026-08-14
- **Dotyczy checkpointów:** CP4, CP5, CP6
- **Materiał źródłowy:** [`docs/design/mockup-inkline.html`](../design/mockup-inkline.html)

---

## Kontekst

Do projektu doszedł mockup wizualny („Inkline") — kompletny shell aplikacji w stylu
desktopowego edytora: pasek menu, toolbar formatowania, sidebar z listą dokumentów,
linijka, strona z tekstem szeryfowym, kursory innych użytkowników z chorągiewkami imion,
zaznaczenia, znaczniki komentarzy, panel boczny z zakładkami Comments/History, status bar.

Design jest dobry i wart przyjęcia. Problem polega na tym, że **mockup pokazuje trzy rzeczy,
których `CLAUDE.md` każe unikać wprost** (sekcja „Czego unikać"):

> Nadmiarowego scope'u (rich text formatting, komentarze, multi-dokument workspace)
> kosztem głębi w core problemie

Mockup zawiera wszystkie trzy: przyciski **B / I / U / S** z kolorami i wyróżnieniem,
panel komentarzy z wątkami, sidebar z workspace'em i wieloma dokumentami.

To jest realny konflikt, a nie kwestia interpretacji. Trzeba go rozstrzygnąć jawnie.

---

## Rozważane opcje

### Opcja A — Zaimplementować mockup w całości

**Za:** demo wygląda najbogaciej, mockup jest odwzorowany 1:1.
**Przeciw:**
- Rich text w CRDT to **inny algorytm**, nie dodatek do istniejącego. Formatowanie wymaga
  markerów rozpiętych na zakresie znaków, które muszą przetrwać współbieżne wstawienia
  w środek zakresu — to jest problem, któremu poświęcono osobną pracę naukową (Peritext).
  Realnie podwaja to zakres CP2 i CP4.
- Komentarze to kolejny CRDT (kotwice tekstowe przeżywające edycję pod nimi) plus model danych.
- Efekt netto: mniej czasu na property testy, obserwowalność i benchmarki — czyli na to,
  co ten projekt ma faktycznie demonstrować.

### Opcja B — Zaimplementować shell, ale przyciski formatowania zostawić dekoracyjne

**Za:** wygląda jak mockup, tanie.
**Przeciw:** **przycisk, który nic nie robi, to kłamstwo w interfejsie.** W projekcie
portfolio to gorszy sygnał niż jego brak — recenzent klika, nie działa, i od tego momentu
nie ufa reszcie. Odrzucone.

### Opcja C — Przyjąć język wizualny, wymienić zawartość na to, co w tym projekcie istnieje

Zachowujemy: paletę, typografię (Source Serif 4 na stronie, Inter w chrome, IBM Plex Mono
na danych technicznych), układ trójkolumnowy, stronę z cieniem na szarym tle, kursory
z chorągiewkami imion, zaznaczenia w kolorze autora, status bar, panel boczny z zakładkami.

Wymieniamy zawartość toolbara i panelu bocznego na funkcje, które w tym projekcie **naprawdę
są** i które są dla niego charakterystyczne.

---

## Decyzja

**Opcja C.**

### Co zostaje z mockupu bez zmian

| Element | Uzasadnienie |
|---|---|
| Cała paleta i typografia | Design jest dobry, nie ma powodu go zmieniać |
| Układ: sidebar / kanwa / panel prawy | Dobrze mapuje się na dokumenty / edytor / historię |
| Strona z cieniem na szarym tle, tekst szeryfowy | Odróżnia „treść" od „narzędzia" — czytelne |
| Kursory z chorągiewką imienia, zaznaczenia w kolorze autora | To jest rdzeń demo (CP4) |
| Avatary presence w prawym górnym rogu | Presence jest w zakresie (`idea.md`) |
| Status bar | Idealne miejsce na stan połączenia i licznik operacji |
| Sidebar z listą dokumentów | Wybór dokumentu jest potrzebny funkcjonalnie; to nie jest „workspace", tylko nawigacja |

### Co znika

| Element | Powód |
|---|---|
| Przyciski **B / I / U / S**, kolory tekstu, wyróżnienie | Rich text poza zakresem (`CLAUDE.md`) |
| Dropdown „Normal text" (nagłówki) | jw. |
| Listy punktowane/numerowane, wstawianie obrazu, link | jw. |
| Panel Comments, znaczniki komentarzy w tekście | Komentarze poza zakresem (`CLAUDE.md`) |
| Linijka (ruler) z marginesami | Sugeruje kontrolę nad layoutem strony, której nie ma |
| Pasek menu File / Edit / View / Insert / Format / Tools | Sześć menu, z których pięć byłoby puste |

### Czym zastępujemy — toolbar

Zamiast formatowania, toolbar dostaje kontrolki, które w tym projekcie coś znaczą:

1. **Undo / redo** — działające, świadome CRDT (cofa własne operacje użytkownika, nie cudze).
   Zostaje z mockupu, bo tam też jest pierwsze.
2. **Symulator sieci** — suwaki opóźnienia, jitteru, utraty pakietów i przycisk „partycja".
   To jest funkcja wprost z `idea.md` („symulator konfliktów"), i jest to najmocniejszy
   element demo: pozwala **na żywo pokazać, że algorytm działa**, zamiast o tym zapewniać.
3. **Wskaźnik stanu połączenia** — online / offline / resynchronizacja, z liczbą operacji
   w buforze offline.

### Czym zastępujemy — panel prawy

Zakładki `Comments | History` → **`Historia | Repliki`**:

- **Historia** — oś czasu wersji z audit trailem (kto, co, kiedy), przewijanie do dowolnego
  punktu, przywracanie. To jest CP5, w pełni w zakresie. Mockup już ma na to gotowy wygląd
  wpisu historii (`.history-entry`), więc design jest do wykorzystania wprost.
- **Repliki** — podgląd stanu wewnętrznego CRDT dla tego dokumentu: version vector każdej
  repliki, liczba żywych znaków vs tombstone'ów, `stableVersion` i to, która replika blokuje
  GC ([ADR-0005](0005-tombstone-gc.md)).

Ten drugi panel to świadoma zamiana funkcji produktowej na funkcję **diagnostyczną**.
W zwykłej aplikacji byłby niepotrzebny. Tutaj jest sensem całego przedsięwzięcia: pokazuje,
że pod spodem faktycznie działa struktura danych, o której mówią ADR-y, i pozwala zobaczyć
tombstone'y oraz stabilność przyczynową na żywo, zamiast czytać o nich w README.

### Nazwa

Mockup wprowadza nazwę **Inkline**. Przyjmujemy ją jako nazwę produktu w UI. Repozytorium
i moduły zostają przy `collabedit` / `dev.collabedit` — zmiana identyfikatorów w kodzie
nie wnosi nic poza kosztem.

---

## Konsekwencje

### Pozytywne
- Cały budżet zostaje w problemie rdzeniowym; design nie kosztuje zakresu.
- **Każdy widoczny element interfejsu coś robi.** Brak dekoracyjnych atrap.
- Symulator sieci w toolbarze zamienia najtrudniejszy do pokazania aspekt projektu
  (poprawność przy współbieżności) w coś, co widać w trakcie 20-sekundowego GIF-a.
- Panel „Repliki" czyni wewnętrzną strukturę CRDT obserwowalną — to samo w sobie
  jest argumentem, że jest napisana od zera, a nie zaimportowana.

### Negatywne
- **Odwzorowanie mockupu nie jest wierne.** Kto porówna repo z mockupem, zobaczy inny
  toolbar i inny panel boczny. Dlatego mockup zostaje w repo razem z tym ADR-em —
  różnica ma być udokumentowana, a nie ukryta.
- **Brak rich textu ogranicza wiarygodność jako „Google Docs light"** — dokument bez
  pogrubienia i nagłówków jest wyraźnie prostszy niż to, co sugeruje strona z tekstem
  szeryfowym i linijką. Trafia do sekcji „co zrobiłbym inaczej w produkcji" wraz z tym,
  że właściwą odpowiedzią jest Peritext, a nie doklejenie znaczników do RGA.
- **Sidebar z listą dokumentów wymaga jednak minimalnego modelu wielu dokumentów** —
  tabela `documents` i tak istnieje ([ADR-0003](0003-historia-zmian.md)), więc koszt jest
  bliski zeru, ale formalnie jest to krok w stronę „workspace", którego mieliśmy unikać.
  Granica: lista i wybór dokumentu tak, foldery/uprawnienia/udostępnianie nie.
- Panel „Repliki" to element, którego **nie ma w żadnym prawdziwym edytorze** — dla osoby
  szukającej produktu będzie dziwny. Jest tu świadomie, bo adresatem repo jest inżynier,
  nie użytkownik końcowy.

### Do zweryfikowania w CP4
- Czy kursory z chorągiewkami z mockupu pozostają czytelne przy 5+ jednoczesnych
  użytkownikach w jednym akapicie, czy chorągiewki zaczynają się nakładać.
- Czy strona o stałej szerokości 620 px dobrze znosi okno podzielone na pół (scenariusz
  demo: dwie przeglądarki obok siebie) — jeśli nie, potrzebny jest wariant zwężony.


---

## Rewizja: diagnostyka znika z interfejsu użytkownika

**Decyzja o umieszczeniu symulatora sieci w toolbarze i podglądu replik CRDT w panelu
bocznym została cofnięta.**

### Co było nie tak

Pierwotne rozumowanie brzmiało: „każdy widoczny element ma coś robić, a te dwa pokazują
dokładnie to, czego projekt dowodzi". Rozumowanie jest poprawne co do **wartości** tych
narzędzi i błędne co do **adresata interfejsu**.

Użytkownik edytora nie wie, czym jest tombstone ani version vector, i nie ma obowiązku
wiedzieć. Suwak „utrata pakietów" nad dokumentem nie sprawia, że produkt wygląda na
przemyślany — sprawia, że wygląda na stanowisko laboratoryjne. Pomyliłem dwie rzeczy:
to, co warto **pokazać recenzentowi repozytorium**, z tym, co ma zobaczyć **użytkownik
otwierający edytor**. To pierwsze należy do README, ADR-ów i testów. To drugie ma wyglądać
na skończony produkt.

### Co zamiast tego

| Element | Było | Jest |
|---|---|---|
| Toolbar | suwaki opóźnienia, jitteru, straty, „Odetnij sieć" | cofnij / ponów — jedyne dwie akcje, które faktycznie są |
| Panel prawy | zakładki „Repliki" i „Historia" | historia zmian, bez zakładek |
| Pasek górny | stan połączenia w języku protokołu | tytuł dokumentu, „Zapisano" / „Praca offline", awatary, „Udostępnij" |
| Pasek stanu | znaki, bloki, tombstone'y, bufor | liczba słów i znaków, ile osób online |

Diagnostyka **nie zniknęła** — jest pod `?debug=1`, poza zasięgiem przypadkowego
użytkownika, i nadal napędza testy chaosu.

### Skutki uboczne, które okazały się poprawkami

Rewizja wymusiła dwie zmiany, które trzeba było zrobić niezależnie:

1. **Cofanie musiało zacząć działać.** Wcześniej `history()` CodeMirrora było wyłączone
   (słusznie — cofałoby cudze zmiany), ale nic go nie zastąpiło. Ctrl+Z nie robił nic,
   co w edytorze tekstu jest brakiem widocznym w pierwszej minucie użycia. Powstał
   `UndoManager` cofający **wyłącznie własne operacje**, ze scalaniem ciągłego pisania
   w jeden krok — bez tego Ctrl+Z zdejmowałby po jednej literze.

2. **Demo przestało polegać na symulatorze.** Scenariusz offline odcina teraz sieć
   **naprawdę** (`context.setOffline`), a nie wstrzymuje wysyłkę w aplikacji. Test stał się
   przez to mocniejszy: obejmuje zerwanie WebSocketa, ponowne połączenie i resynchronizację,
   czyli pełną ścieżkę użytkownika z padniętym wi-fi. Przy okazji wyszło, że interfejs
   pokazywał „Zapisano" jeszcze kilkanaście sekund po utracie łączności — do czasu wygaśnięcia
   heartbeatu. Dodany nasłuch zdarzeń `online`/`offline` przeglądarki naprawia to natychmiast.
