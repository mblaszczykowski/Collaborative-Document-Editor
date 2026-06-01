# ADR-0005 — Garbage collection tombstone'ów

- **Status:** Zaakceptowany
- **Data:** 2026-08-14
- **Dotyczy checkpointów:** CP6 (implementacja), CP8 (pomiar narzutu)

---

## Kontekst

Konsekwencja ADR-0001: usunięcie znaku w RGA nie usuwa węzła, tylko oznacza go jako tombstone.
Węzeł musi zostać, bo operacja od innej repliki może wskazywać go jako `originLeft` — usunięcie
go oznaczałoby, że nie ma do czego doczepić przychodzącego znaku.

Skutek: **struktura rośnie monotonicznie z liczbą wykonanych edycji, nie z rozmiarem tekstu.**
Dokument, w którym napisano i skasowano 10 MB tekstu, a został pusty, nadal zajmuje pamięć
proporcjonalną do 10 MB. To nie jest teoretyczny problem — to główny praktyczny zarzut wobec
CRDT dla tekstu.

Pytanie: **kiedy wolno bezpiecznie usunąć tombstone?**

---

## Rozważane opcje

### Opcja A — Nigdy nie usuwać

**Za:** trywialnie poprawne, zero kodu.
**Przeciw:** długo żyjący dokument staje się nieużywalny. Zużycie pamięci i rozmiar snapshotu
rosną bez ograniczenia. Nie do obrony w projekcie, który ma pokazywać zrozumienie CRDT —
bo to właśnie tu leży trudność.

### Opcja B — Usuwać po upływie czasu (np. tombstone starszy niż 24 h)

**Za:** proste, przewidywalne.
**Przeciw:** **niepoprawne.** Czas nie ma nic wspólnego z tym, czy jakaś replika nadal
potrzebuje tego węzła. Klient offline od 25 h wraca z operacją wskazującą na skasowany
tombstone → operacja nie ma się do czego przyczepić. Czas jest proxy dla przyczynowości,
i to złym proxy.

### Opcja C — Usuwać po osiągnięciu stabilności przyczynowej (causal stability)

Tombstone wolno usunąć, gdy **każda replika, która może jeszcze przysłać operację,
już widziała jego usunięcie**. Wtedy nikt nie może przysłać operacji zaczepionej o ten węzeł.

Formalnie: dla dokumentu utrzymujemy `stable = min` po wszystkich aktywnych replikach
z ich version vectorów. Operacje w całości poniżej `stable` są przyczynowo stabilne —
żadna przyszła operacja nie może być z nimi współbieżna.

**Za:** poprawne z definicji.
**Przeciw:** wymaga wiedzy o wszystkich replikach, które „mogą jeszcze przysłać operację" —
a tego w otwartym systemie nie da się wiedzieć na pewno. Replika offline od miesiąca może
wrócić. Zbiór aktywnych replik trzeba więc **domknąć decyzją administracyjną**, i to jest
sedno tej decyzji.

### Opcja D — Rekonstrukcja: skompaktuj cały dokument i wymuś pełny resync u wszystkich

Przepisz strukturę bez tombstone'ów, nadaj nowe identyfikatory, każ wszystkim klientom
pobrać stan od nowa.

**Za:** maksymalna kompresja.
**Przeciw:** to nie jest GC, to reset. Każdy klient z niezsynchronizowanymi zmianami je traci.
Ma sens jako operacja awaryjna, nie jako rutyna.

---

## Decyzja

**Opcja C — GC oparty na stabilności przyczynowej — z jawną, udokumentowaną granicą
domykającą zbiór replik.**

### Mechanizm

1. Serwer utrzymuje per dokument mapę `replicaId → version vector` dla replik uznanych
   za **żywe**: podłączonych teraz albo rozłączonych krócej niż **okno GC**.
2. `stableVersion` = po współrzędnych minimum z tych version vectorów (plus version vector
   stanu zapisanego w Postgresie — baza też jest repliką).
3. Tombstone, którego operacja usunięcia jest w całości poniżej `stableVersion` i którego
   węzeł nie jest `originLeft` żadnego żywego węzła, jest **usuwany fizycznie** przy
   następnym snapshotowaniu (ADR-0003). GC jest częścią snapshotowania, nie osobnym
   procesem — snapshot i tak przepisuje całą strukturę, więc kompakcja jest darmowa.
4. **Okno GC: 7 dni.** Uzasadnienie: musi być wyraźnie dłuższe niż realistyczna sesja offline
   (praca w podróży, weekend), a jednocześnie skończone, bo inaczej wracamy do opcji A.
   Tydzień pokrywa „wyjechałem na urlop z otwartym laptopem"; miesiąc już nie zmienia
   praktycznie nic dla użytkownika, a wydłuża czas życia śmieci.

### Co się dzieje z klientem, który wraca po oknie GC — i dlaczego NIE udajemy scalania

Klient offline dłużej niż 7 dni wraca z operacjami wskazującymi na `originLeft`, którego
już nie ma. **Nie da się tego uczciwie scalić** — informacja o pozycji tych znaków została
bezpowrotnie utracona przez GC.

Rozważane reakcje:

- *Doklej na koniec dokumentu* — scalenie „się udaje", ale tekst ląduje w losowym miejscu.
  Cicha korupcja treści. **Odrzucone.**
- *Zignoruj operacje* — cicha utrata pracy użytkownika. **Odrzucone.**
- *Odrzuć i wyeksportuj* — **wybrane.** Serwer odrzuca operacje z `originLeft` poza
  `stableVersion`, odpowiada `RESYNC_REQUIRED`, klient pobiera aktualny stan i **zapisuje
  swoją lokalną wersję jako osobny dokument „Odzyskany szkic (offline od …)"** z jawnym
  komunikatem dla użytkownika.

Utrata pracy bez powiadomienia jest znacznie gorsza niż jawne „nie potrafię tego scalić,
oto twoja kopia". Ta decyzja jest opisana w README w sekcji Failure modes, bo jest to
prawdziwe ograniczenie systemu, a nie szczegół implementacyjny.

### Metryki (CP7)

- `doc_tombstone_ratio` — tombstone'y / wszystkie węzły. Wysoka wartość utrzymująca się
  w czasie oznacza, że GC nie działa (np. jeden zawieszony klient blokuje `stableVersion`
  dla całego dokumentu — realny scenariusz).
- `crdt_gc_removed_nodes_total`
- `crdt_gc_blocked_by_replica` — która replika najbardziej opóźnia `stableVersion`.
  Bez tego diagnostyka „dlaczego GC stoi" jest zgadywanką.

---

## Konsekwencje

### Pozytywne
- GC jest **poprawny z definicji**, nie heurystyczny — nie ma scenariusza, w którym usuwamy
  węzeł nadal komuś potrzebny (w granicach okna).
- Zero dodatkowego kosztu wykonania: GC dzieje się przy snapshotowaniu, które i tak przepisuje
  strukturę.
- Granica systemu jest jawna i mierzalna, a nie ukryta.

### Negatywne
- **Jeden wolny lub zawieszony klient blokuje GC dla całego dokumentu.** `stableVersion` to
  minimum — replika, która jest podłączona, ale nie potwierdza postępu, zatrzymuje kompakcję
  wszystkim. Mitygacja: klient bez potwierdzenia postępu przez **60 s** mimo aktywnego
  połączenia jest uznawany za niereagującego i wykluczany z liczenia `stableVersion`
  (przy jednoczesnym pozostawieniu połączenia). Wartość dobrana jako ~2× tolerancja
  heartbeatu z ADR-0002, żeby nie wykluczać klienta wcześniej, niż transport uzna go za żywego.
- **Klient offline > 7 dni traci możliwość scalenia** i dostaje osobny dokument.
  Świadome, jawnie zakomunikowane ograniczenie.
- **GC zwiększa złożoność serializacji snapshotu** — snapshot musi nieść `stableVersion`,
  bo instancja ładująca go musi wiedzieć, czego już nie ma.
- Okno 7 dni to **zgadnięta wartość** oparta na intuicji o zachowaniu użytkowników, nie na
  danych. W produkcji dobrałbym ją z telemetrii długości sesji offline. Zapisane wprost,
  żeby nie udawać, że liczba jest wyliczona.
- Mechanizm nie chroni przed dokumentem, który jest **cały czas** aktywnie edytowany przez
  klienta trzymającego stare version vectory — teoretycznie GC może nigdy nie zadziałać.
  **Do sprawdzenia w CP8**, czy to realny scenariusz przy syntetycznym obciążeniu.

### Do zweryfikowania w CP8
- Faktyczny narzut pamięciowy: MB na dokument o 1 MB tekstu z historią 10× nadpisania,
  przed GC i po GC.
- Czy GC realnie odpala się przy typowym wzorcu użycia, czy `stableVersion` stoi w miejscu.
- Koszt czasowy kompakcji przy snapshotowaniu dużego dokumentu — czy nie wprowadza
  zauważalnej pauzy.
