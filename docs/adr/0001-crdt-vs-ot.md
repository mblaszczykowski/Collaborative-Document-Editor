# ADR-0001 — Algorytm synchronizacji: RGA (CRDT) zamiast OT

- **Status:** Zaakceptowany
- **Data:** 2026-08-14
- **Dotyczy checkpointów:** CP2 (implementacja), CP8 (weryfikacja wydajnościowa)

---

## Kontekst

Rdzeń problemu: dwóch lub więcej użytkowników edytuje ten sam dokument tekstowy jednocześnie,
bez centralnego lockowania. Dwie edycje mogą dotrzeć do różnych replik w różnej kolejności,
a mimo to wszystkie repliki muszą skończyć z **identycznym** tekstem — i to tekstem, który
zachowuje intencję obu autorów, a nie po prostu jakimkolwiek deterministycznym wynikiem.

Naiwne podejścia i dlaczego natychmiast upadają:

- **Last-write-wins na całym dokumencie** — użytkownik A pisze w akapicie 1, B w akapicie 5,
  zapis B nadpisuje cały dokument. Praca A znika bez śladu. To nie jest edge case, to zdarza się
  przy pierwszym równoległym użyciu.
- **LWW per znak / per pozycja** — pozycja (offset) nie jest stabilnym identyfikatorem.
  Gdy A wstawi znak na pozycji 3, wszystkie operacje B odnoszące się do pozycji > 3 są
  o jeden przesunięte. Offsety wymagają transformacji — czyli tak naprawdę OT.
- **Blokada dokumentu / sekcji** — działa, ale przestaje to być edytor kolaboracyjny.

Realny wybór jest między dwiema rodzinami algorytmów: **Operational Transformation (OT)**
i **Conflict-free Replicated Data Types (CRDT)**.

Ograniczenia tego projektu, które ważą na decyzji:
1. Algorytm ma być **napisany samodzielnie**, nie zaimportowany (`CLAUDE.md`, sekcja
   „Czego unikać"). Odpada użycie Yjs/Automerge/ShareDB jako czarnej skrzynki.
2. Poprawność ma być **udowodniona testami property-based**, nie zademonstrowana kilkoma
   ręcznymi przypadkami.
3. Musi działać w trybie offline z późniejszym scaleniem — klient może być odłączony
   przez minuty i wrócić z lokalnym buforem operacji.
4. Musi dać się rozproszyć na wiele instancji backendu (ADR-0004).

---

## Rozważane opcje

### Opcja A — Operational Transformation (OT)

Każda operacja jest wyrażona w offsetach (`insert(pos, char)`, `delete(pos)`). Gdy przychodzi
operacja współbieżna, transformujemy ją względem operacji, których nadawca jeszcze nie widział,
korygując offsety. Historycznie użyte w Google Wave i Google Docs.

**Za:**
- Operacje są małe i tanie w transporcie — `insert(42, 'a')` to kilkanaście bajtów.
- Zero narzutu pamięciowego na strukturę: dokument to zwykły string. Brak tombstone'ów.
- Naturalnie mapuje się na model edytora (offsety), więc integracja z UI jest trywialna.

**Przeciw:**
- Poprawność wymaga spełnienia własności **TP1 i TP2**. TP2 (transformacja trzech
  współbieżnych operacji daje ten sam wynik niezależnie od kolejności) jest notorycznie
  trudna — w literaturze udokumentowano błędy TP2 w opublikowanych i recenzowanych algorytmach
  OT, znajdowane latami po publikacji. To nie jest problem „uważnego programowania".
- Systemy OT w praktyce omijają TP2, wprowadzając **centralny serwer nadający porządek
  totalny** (tak działa Google Docs). To działa, ale:
  - serwer staje się punktem serializacji — sprzeczne z ADR-0004 (wiele równorzędnych instancji),
  - offline na dłużej niż trywialny czas wymaga długiego łańcucha transformacji przy powrocie,
  - i przede wszystkim: „algorytm jest poprawny, bo serwer wymusza kolejność" to znacznie
    słabsza własność niż „algorytm jest poprawny matematycznie".
- Testowanie property-based jest mniej naturalne: nie da się losowo permutować kolejności
  dostarczenia, skoro poprawność zależy od tego, że serwer tę kolejność ustala.

### Opcja B — CRDT, rodzina Logoot / LSEQ (identyfikatory gęste)

Każdy znak dostaje identyfikator pozycyjny z gęstego, uporządkowanego zbioru (ciąg liczb
z porównaniem leksykograficznym). Między dwoma dowolnymi identyfikatorami zawsze da się
wygenerować nowy. Brak tombstone'ów przy wstawianiu.

**Za:**
- Usunięcie znaku to faktyczne usunięcie — brak narzutu tombstone'ów przy delete.
- Prosta koncepcyjnie: porównaj identyfikatory, posortuj.

**Przeciw:**
- **Rozrost identyfikatorów.** Przy pisaniu sekwencyjnym w to samo miejsce (czyli: normalne
  pisanie od lewej do prawej) identyfikatory rosną w długości. W patologicznych przypadkach
  identyfikator jednego znaku przekracza rozmiar samego znaku o rzędy wielkości. LSEQ łagodzi
  to strategią alokacji zmienną w zależności od poziomu, ale nie eliminuje.
- **Silna anomalia przeplotu.** Dwóch userów piszących równolegle różne słowa w tym samym
  miejscu potrafi dostać wynik z literami przeplecionymi znak po znaku (`hweolrllod`).
  To najgorszy możliwy failure mode — nie gubi danych, ale produkuje nonsens.

### Opcja C — CRDT, rodzina RGA / YATA (identyfikatory względne, tombstone'y)

Każdy znak ma identyfikator `(replicaId, counter)` i wskaźnik na **lewego sąsiada w momencie
wstawienia** (`originLeft`). Porządek wśród rodzeństwa wstawionego po tym samym originie
rozstrzyga deterministyczna reguła (u nas: malejąco po `(counter, replicaId)`). Usunięcie
to tombstone, bo węzeł może być czyimś originem.

RGA to podstawa m.in. Yjs (YATA jest wariantem z dodatkowym `originRight`) i Automerge.

**Za:**
- **Zbieżność jest własnością struktury, nie protokołu.** Operacje są przemienne i idempotentne;
  dowolna kolejność dostarczenia daje ten sam wynik. To dokładnie ta własność, którą można
  udowodnić testem property-based losującym permutacje dostarczenia — i to jest najważniejszy
  test w tym projekcie.
- Brak potrzeby centralnego arbitra → domyka ADR-0004 (każda instancja backendu jest po prostu
  kolejną repliką, zawodny broadcast przez Redis jest bezpieczny z definicji).
- Offline jest darmowy: bufor operacji + odtworzenie po powrocie, bez transformacji.
- Identyfikatory mają stały rozmiar (`int` + `long`), nie rosną z długością dokumentu.
- Pisanie sekwencyjne kompresuje się do bloków (run-length), bo kolejne znaki mają kolejne
  countery i wspólny origin.

**Przeciw:**
- **Tombstone'y rosną w nieskończoność** bez garbage collection. Dokument, w którym napisano
  i skasowano 1 MB tekstu, trzyma w pamięci 1 MB niewidocznych węzłów. To wymaga osobnej,
  nietrywialnej decyzji — ADR-0005.
- **Mapowanie offset ↔ identyfikator.** Edytor mówi „wstaw na pozycji 4271", CRDT potrzebuje
  identyfikatora węzła. Naiwnie to O(n) skanowania na każde naciśnięcie klawisza. Wymaga
  struktury indeksowanej (ADR w CP2 / plan §2).
- **RGA nie jest maksymalnie non-interleaving** — patrz sekcja „Znane ograniczenie" niżej.
- Operacje są większe niż w OT (niosą identyfikator + origin zamiast samego offsetu).

### Opcja D — Fugue (maksymalnie non-interleaving)

Wariant CRDT z 2023 (Weidner & Kleppmann) zaprojektowany tak, by wyeliminować anomalię
przeplotu w obu kierunkach, przy zachowaniu własności RGA.

**Za:** rozwiązuje jedyną poważną wadę jakościową RGA.
**Przeciw:** istotnie więcej pracy przy jednoczesnym braku różnicy w tym, co projekt
demonstruje (zrozumienie zbieżności, tombstone'ów, przyczynowości). Koszt oszacowany
na +2–3 dni z ~14-dniowego budżetu.

---

## Decyzja

**Implementujemy RGA (opcja C)** — własna implementacja w Javie (`crdt-core`, moduł bez
żadnych zależności od Springa) oraz bliźniacza implementacja w TypeScripcie po stronie klienta.

Kluczowe rozstrzygnięcia projektowe:

| Element | Wybór | Dlaczego |
|---|---|---|
| Identyfikator | `(replicaId: int, counter: long)` | Stały rozmiar, brak rozrostu jak w Logoot |
| Punkt zaczepienia | `originLeft` | Wariant klasycznego RGA; `originRight` (YATA) dodaje złożoność bez korzyści przy naszym zakresie |
| Rozstrzyganie rodzeństwa | malejąco po `(counter, replicaId)` | Deterministyczne i totalne bez arbitra; `replicaId` łamie remisy counterów |
| Usunięcie | tombstone | Węzeł może być originem operacji, której jeszcze nie widzieliśmy |
| Reprezentacja | bloki run-length, dzielone przy wstawieniu w środek | Bez tego dokument 100k znaków to 100k obiektów na stercie |
| Dedup / przyczynowość | version vector + bufor operacji osieroconych | Retransmisja po reconnect nie może zduplikować tekstu |

**Fugue (opcja D) odrzucone** ze względu na budżet — świadomie, z zapisem w README w sekcji
„co zrobiłbym inaczej".

**OT (opcja A) odrzucone** przede wszystkim dlatego, że jego poprawność w praktyce opiera się
na centralnym serwerze porządkującym, co jest sprzeczne z modelem wielu równorzędnych instancji
z ADR-0004 — a wtórnie dlatego, że TP2 jest pułapką, w którą wpadły recenzowane publikacje.

---

## Znane ograniczenie: anomalia przeplotu (interleaving)

RGA **nie** gwarantuje, że współbieżnie wpisane ciągi znaków pozostaną zwarte we wszystkich
przypadkach. Klasyczny scenariusz problematyczny to współbieżne wstawianie „od prawej do lewej"
(np. dwóch userów wielokrotnie wciska klawisz w tym samym miejscu, cofając kursor).

Podejście w tym projekcie — **nie ukrywamy tego**:

1. Property test `concurrentTyping_staysContiguous` sprawdza własność non-interleaving
   dla pisania sekwencyjnego (RGA to przechodzi).
2. Osobny test `knownLimitation_backwardInterleaving` konstruuje przypadek, w którym RGA
   przeplata, i **jest oznaczony jako oczekiwane ograniczenie** z linkiem do tego ADR-a.
   Zostaje w repo jako wykonywalna dokumentacja granicy algorytmu.
3. README opisuje, że rozwiązaniem jest Fugue, i dlaczego nie zmieściło się w zakresie.

Test, który dokumentuje wadę, jest wart więcej niż jego brak. Usunięcie go byłoby udawaniem,
że algorytm ma własność, której nie ma.

---

## Konsekwencje

### Pozytywne
- Zbieżność da się **udowodnić** testem property-based na losowych permutacjach dostarczenia —
  a nie tylko zademonstrować na kilku przypadkach. To jest główny artefakt techniczny projektu.
- Backend skaluje się poziomo bez sticky sessions i bez rozproszonego locka (ADR-0004).
- Obsługa offline nie wymaga osobnego mechanizmu — wynika z algorytmu.

### Negatywne
- **Tombstone'y wymagają GC**, a GC w CRDT wymaga stabilności przyczynowej. To cała osobna
  decyzja (ADR-0005) i realny failure mode dla klientów offline przez długi czas.
- **Algorytm istnieje w dwóch implementacjach** (Java + TypeScript). To dług: dwie okazje
  do błędu, jedna specyfikacja. Mitygacja — korpus wektorów konformancyjnych generowany
  przez testy w Javie i odtwarzany przez suite TS w CI. Rozjazd nie może przejść po cichu,
  ale nadal *podwaja koszt każdej zmiany w algorytmie*.
- **Operacje są większe niż w OT.** Wpływ na przepustowość — do zmierzenia w CP8, nie zgaduję.
- **Naiwne mapowanie offset↔id jest O(n)**, więc pierwsza wersja będzie wolna na dużych
  dokumentach. To celowe: chcemy zmierzyć, zanim zoptymalizujemy.
- **Anomalia przeplotu** w wybranych scenariuszach (patrz wyżej).

### Do zweryfikowania w CP8
- Realny punkt degradacji: przy jakim rozmiarze dokumentu i jakiej liczbie równoczesnych
  userów p95 latencji propagacji przestaje być akceptowalne.
- Faktyczny narzut pamięciowy tombstone'ów przed i po GC, w MB, na dokumencie o znanej historii.
- Czy run-length blocking daje spodziewaną redukcję liczby obiektów przy realistycznym
  wzorcu edycji (a nie tylko przy syntetycznym pisaniu ciągiem).

---

## Źródła

- Roh, Jeon, Kim, Lee — *Replicated abstract data types: Building blocks for collaborative
  applications* (RGA, 2011)
- Nicolaescu, Jahns, Derntl, Klamma — *Near Real-Time Peer-to-Peer Shared Editing on
  Extensible Data Types* (YATA, 2016)
- Weidner, Kleppmann — *The Art of the Fugue: Minimizing Interleaving in Collaborative
  Text Editing* (2023)
- Sun, Ellis — *Operational Transformation in Real-Time Group Editors: Issues, Algorithms,
  and Achievements* (1998) — źródło TP1/TP2
- Kleppmann, Beresford — *A Conflict-Free Replicated JSON Datatype* (2017)
