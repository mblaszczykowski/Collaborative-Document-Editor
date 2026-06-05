# Plan implementacji — Collaborative Document Editor

Dokument roboczy. Powstał przed pierwszą linijką kodu, na bazie `idea.md` i `CLAUDE.md`.
Aktualizowany w miarę postępu — jeśli decyzja z planu okaże się zła w praktyce, poprawiamy
tutaj **i** commitujemy zmianę planu razem z powodem (historia iteracji jest częścią artefaktu).

---

## 0. Stan wyjściowy i blokady (do zrobienia zanim ruszy checkpoint 1)

| Rzecz | Status | Co zrobić |
|---|---|---|
| Repozytorium git | ❌ brak | `git init` + `.gitignore` + pierwszy commit. Bez tego tracimy narrację commitów wymaganą przez CLAUDE.md §2 |
| Java | ⚠️ domyślna 25-ea, jest też 21.0.1 | Celujemy w **21 LTS** przez `maven.compiler.release=21` — bajtkod jest ten sam niezależnie od tego, na czym Maven startuje |
| Node | ✅ v20.18.1 | OK dla Vite 5 / React 19 |
| **Docker** | ❌ **brak** | **BLOKADA.** Bez niego nie ma: Testcontainers (§3.2 wymagań), Postgresa lokalnie, Docker Compose, stacku Prometheus/Grafana. Potrzebne Docker Desktop albo Colima (`brew install colima docker docker-compose`) |
| ffmpeg / gifski | ❌ brak | Potrzebne dopiero w checkpoincie 9 (GIF demo). `brew install ffmpeg gifski` |

**Docker jest blokerem dla checkpointów 3, 7, 8, 9** — checkpointy 1 i 2 (ADR-y + rdzeń CRDT)
można zrobić bez niego, bo `crdt-core` to czysta Java bez I/O. Sugeruję instalować Dockera
równolegle z checkpointem 1, żeby nie stać.

---

## 1. Architektura docelowa — jedna strona

```
┌─ frontend (React + TS, Vite) ──────────────────────────────┐
│  CodeMirror 6 (tylko warstwa widoku + dekoracje kursorów)  │
│  crdt-ts/          ← własna implementacja RGA (bliźniak Javy)│
│  transport/        ← STOMP client + bufor offline + symulator sieci │
└────────────────────────┬───────────────────────────────────┘
                         │ WebSocket / STOMP (traceparent w headerach)
┌────────────────────────▼───────────────────────────────────┐
│ app (Spring Boot 3, Java 21)                               │
│  ws/         StompController, presence, per-doc session reg│
│  domain/     DocumentReplica (in-memory), OpApplier        │
│  persist/    op_log (append-only) + snapshots (Postgres)   │
│  history/    time-travel: snapshot + replay do punktu T    │
│  cluster/    Redis pub/sub fan-out + anti-entropy z op_log │
│  otel/       tracing, metryki Micrometer → Prometheus      │
└──────┬──────────────────────┬──────────────────────────────┘
       │                      │
   PostgreSQL             Redis (fast path)
   (źródło prawdy)        (best-effort broadcast)
                         
crdt-core/  ← osobny moduł Mavena, ZERO zależności od Springa.
             Czysty algorytm + testy property-based (jqwik) + JMH.
```

### Kluczowa decyzja architektoniczna: każda instancja backendu jest repliką CRDT

Nie ma „właściciela dokumentu", nie ma sticky sessions, nie ma rozproszonego locka.
Każda instancja trzyma własną replikę dokumentu i scala operacje — czyli robi dokładnie to,
do czego CRDT został wymyślony. Redis pub/sub to **ścieżka szybka, zawodna** (at-most-once).
Postgres `op_log` to **ścieżka wolna, autorytatywna** — instancja, która wykryje lukę w
version vectorze, dociąga brakujące operacje z bazy (anti-entropy / read-repair).

To jest ADR-004 i jest to najmocniejszy punkt architektury: zawodny broadcast jest tu
*bezpieczny z definicji*, a nie „bezpieczny bo mamy nadzieję".

---

## 2. Rdzeń algorytmu — co dokładnie implementujemy

### Wybór: RGA (Replicated Growable Array), wariant z left-origin

- `Id = (replicaId: int, counter: long)` — licznik Lamporta per replika.
- Węzeł znaku: `{ id, originLeft: Id, value, deleted: boolean }`.
- **Insert**: nowy węzeł wskazuje lewego sąsiada w momencie wstawienia. Wśród rodzeństwa
  wstawionego po tym samym origin — porządek malejąco po `(counter, replicaId)`. Deterministyczny
  porządek totalny bez arbitra.
- **Delete**: tombstone (flaga), nie usunięcie z listy — bo inne repliki mogą jeszcze
  wskazywać na ten węzeł jako origin.
- **Dedup / dostarczanie przyczynowe**: version vector per replika + bufor operacji, których
  przyczyny jeszcze nie widzieliśmy.
- **Run-length encoding**: ciągłe pisanie („user wpisał 20 znaków") to jedna operacja z bazowym
  `id` i długością; węzeł dzieli się dopiero gdy ktoś wstawi coś w jego środek (jak `Item` w Yjs).
  Bez tego dokument na 100 tys. znaków to 100 tys. obiektów.

### Świadomie udokumentowana słabość: anomalia przeplotu (interleaving)

RGA nie jest maksymalnie non-interleaving (patrz: Weidner & Kleppmann, *Fugue*). Istnieją
sekwencje współbieżnych wstawień, gdzie litery dwóch autorów się przeplatają zamiast zostać
zwartymi blokami.

**Plan:** napisać test property-based `concurrentTyping_staysContiguous`, znaleźć przypadek,
w którym RGA go łamie, i **zostawić go w repo jako jawnie oznaczony known-limitation test**
(`@Disabled("ADR-001: znana anomalia RGA, patrz sekcja Trade-offs")`) z linkiem do ADR.
Uczciwe udokumentowanie granicy własnego algorytmu > udawanie, że granicy nie ma.

Opcja rozszerzenia (jeśli starczy czasu): drugi backend algorytmu (Fugue) za tym samym
interfejsem + ten sam zestaw property testów puszczony na obu. To by było mocne, ale to
+2-3 dni — decyzja do podjęcia po checkpoincie 2.

### Optymalizacja jako narracja, nie jako założenie

Robimy **dwie** implementacje mapowania `index ↔ CRDT id`, za tym samym interfejsem:

1. **Naiwna** — liniowe skanowanie listy. O(n) na operację. Prosta, oczywiście poprawna.
2. **Indeksowana** — order-statistic tree / skip-list nad blokami, trzymająca licznik
   widocznych znaków. O(log n).

Obie przechodzą **ten sam** zestaw property testów (to dowód, że optymalizacja niczego nie
zepsuła), a JMH pokazuje różnicę w liczbach na dokumentach 1k / 10k / 100k / 1M znaków.
Ta sekwencja commitów — naiwne → zmierzone → zoptymalizowane → ta sama poprawność — jest
warta więcej niż od razu wrzucony sprytny kod.

### Dwie implementacje (Java + TypeScript) i jak nie dopuścić do rozjazdu

CRDT musi istnieć po obu stronach (klient edytuje offline lokalnie). To realny koszt: dwa
kody, jedna specyfikacja. Rozwiązanie: **korpus wektorów konformancyjnych**.

Property testy w Javie generują losowe sekwencje operacji i zapisują je jako JSON
(`crdt-core/src/test/resources/conformance/*.json`: operacje + oczekiwany stan końcowy).
Suite w TypeScripcie odtwarza dokładnie te same wektory i musi dojść do tego samego tekstu.
CI odpala oba. Rozjazd implementacji przestaje być możliwy po cichu.

---

## 3. Checkpointy

Po każdym checkpoincie **stop i pytanie, czy kontynuować** (CLAUDE.md).
Estymaty zakładają pracę z Claude Code, nie „ręcznie od zera".

---

### CP1 — ADR-y + szkielet (0,5–1 dnia) — bez kodu domenowego

**Deliverables:**
- `git init`, `.gitignore`, `.editorconfig`, pierwszy commit
- `docs/adr/0001-crdt-vs-ot.md` — RGA vs Logoot/LSEQ vs YATA/Yjs vs Fugue vs OT.
  Konsekwencje negatywne: tombstone growth, anomalia przeplotu, podwójna implementacja
- `docs/adr/0002-transport.md` — STOMP/WS vs raw WS vs SSE+POST vs WebTransport.
  Uzasadnienie heartbeatu i jego wartości liczbowej (nie „bo default")
- `docs/adr/0003-historia-zmian.md` — event log vs periodic snapshots (decyzja: oba,
  snapshot to cache nad logiem; kompromis miejsce/czas odtworzenia)
- `docs/adr/0004-skalowanie-poziome.md` — instancja jako replika + Redis fast path +
  anti-entropy z op_log; alternatywa (document affinity) i dlaczego odrzucona
- `docs/adr/0005-tombstone-gc.md` — causal stability, kiedy wolno skasować tombstone
- Szkielet Mavena: `pom.xml` (parent) + moduły `crdt-core`, `app`
- `frontend/` — Vite + React + TS, pusty
- `docker-compose.yml` — Postgres, Redis, Prometheus, Grafana, OTel Collector (z komentarzami
  *dlaczego* przy każdym nietrywialnym ustawieniu)
- CI: GitHub Actions, na razie tylko build + lint
- README: sekcje-zaślepki z tytułami, żeby wiadomo było co dopisujemy po drodze

**Exit criteria:** `./mvnw install` i `npm run build` przechodzą, ADR-y opisują 5 decyzji,
w każdym jest sekcja „Konsekwencje negatywne" która nie jest pusta.

---

### CP2 — Rdzeń CRDT + property testy (2–3 dni) — **serce projektu**

**Deliverables:**
- `crdt-core`: `Id`, `VersionVector`, `Op` (Insert/Delete), `RgaDocument`, `IndexMap`
  (naiwna + indeksowana), serializacja binarna operacji
- Property testy (jqwik) — każdy odpowiada na konkretne pytanie:

| Property | Jaki failure mode wyłapuje |
|---|---|
| `convergence` — N replik, losowe operacje, losowa kolejność dostarczenia → identyczny tekst | Rdzeń: brak przemienności scalania = ciche gubienie zmian |
| `idempotence` — dostarczenie tej samej operacji 2× nic nie zmienia | Retransmisja po reconnect duplikuje tekst |
| `causalDelivery` — operacja przed swoim originem trafia do bufora, nie do stanu | Sieć zmienia kolejność → NPE albo znak w złym miejscu |
| `deleteCommutesWithInsert` — usunięcie znaku i wstawienie obok siebie w dowolnej kolejności | Wyścig kursor/backspace = zjedzony sąsiedni znak |
| `naiveEqualsIndexed` — obie implementacje IndexMap na tym samym wejściu | Optymalizacja O(log n) wprowadziła regres poprawności |
| `concurrentTyping_staysContiguous` | Anomalia przeplotu — **oczekujemy że padnie**, dokumentujemy |
| `gcPreservesText` — GC tombstone'ów nie zmienia widocznego tekstu | GC kasuje węzeł, który był czyimś originem |

- Determinizm testów: stały seed w CI + nightly run z losowym seedem i 10× większą liczbą prób
  (znalezione kontrprzykłady lądują w repo jako regression testy)
- Generator wektorów konformancyjnych (JSON) dla frontendu
- JMH: benchmark insert/delete/merge na 1k/10k/100k/1M znaków, wynik do README

**Exit criteria:** wszystkie property testy zielone (poza jawnie oznaczonym known-limitation),
JMH pokazuje liczbami, że wersja indeksowana skaluje się log-liniowo, a naiwna kwadratowo.
**Nie idziemy dalej, dopóki to nie jest solidne** (CLAUDE.md).

---

### CP3 — WebSocket + persystencja (2 dni)

**Deliverables:**
- STOMP endpoint, kanały: `/topic/doc/{id}/ops`, `/topic/doc/{id}/presence`, `/app/doc/{id}/op`
- Protokół: `ClientOp` → serwer scala → `ServerAck(opId, serverSeq)` + broadcast do reszty.
  Klient nie czeka na ack żeby pokazać własny znak (local-first, zero input lag)
- Postgres: `documents`, `operations` (PK = `(doc_id, replica_id, counter)` → idempotencja
  zapisu za darmo), `snapshots`, `document_members`
- Migracje: Flyway
- Batchowanie zapisu operacji (grupowy insert co N ms) — z komentarzem o kompromisie
  trwałość/przepustowość
- Auth: JWT, uproszczony in-memory user store — świadomie, do sekcji „co inaczej w produkcji"
- **Testy integracyjne z Testcontainers** (prawdziwy Postgres): dwóch klientów WS edytuje
  ten sam dokument → obaj widzą to samo; restart aplikacji → stan odtworzony z op_log

**Exit criteria:** dwa klienty (test) zbieżne przez prawdziwy stack, stan przeżywa restart.

---

### CP4 — Frontend: edytor + kursory (2–3 dni)

**Deliverables:**
- `crdt-ts` — port RGA, przechodzi wektory konformancyjne z CP2
- CodeMirror 6 jako **warstwa widoku** (nie model): transakcje CM6 → operacje CRDT →
  z powrotem dekoracje. Uzasadnienie w ADR-006: raw contentEditable to miesiąc walki
  z IME/composition/undo przeglądarki, a `idea.md` explicite dopuszcza bibliotekę jako UI layer
- Zdalne kursory i zaznaczenia — pozycja kursora trzymana jako **CRDT id, nie offset**
  (offset rozjeżdża się przy zdalnej edycji przed kursorem — to klasyczny bug, warto o nim
  napisać w README)
- Presence: lista userów, „X pisze", kolory per user
- Optimistic local apply + rekoncyliacja po ack

**Exit criteria:** dwie karty przeglądarki, jednoczesne pisanie w tym samym akapicie, brak
skakania kursora, brak gubienia znaków.

---

### CP5 — Historia zmian / wersjonowanie (1–1,5 dnia)

**Deliverables:**
- Time travel: `GET /api/docs/{id}/at?t=...` — najbliższy wcześniejszy snapshot + replay
  operacji z op_log do punktu T
- Audit trail: kto, co, kiedy (na bazie `replica_id → user_id`)
- Restore: przywrócenie wersji jako **nowe operacje**, nie nadpisanie logu (log pozostaje
  append-only — inaczej tracimy audytowalność i psujemy zbieżność u klientów online)
- UI: timeline / lista wersji, diff między wersjami, podgląd

**Exit criteria:** można cofnąć się do dowolnego punktu, przywrócić go, a inne otwarte klienty
zobaczą to jako normalną edycję.

---

### CP6 — Offline / reconnect (1,5 dnia)

**Deliverables:**
- Bufor operacji offline w IndexedDB (przeżywa refresh karty, nie tylko utratę sieci)
- Reconnect: wymiana version vectorów → serwer dosyła braki, klient dosyła swoje
- Backoff wykładniczy z jitterem (uzasadniony liczbowo, nie z palca)
- **Tombstone GC + polityka dla bardzo starych klientów**: klient offline dłużej niż okno GC
  wraca z operacjami wskazującymi na skasowane tombstone'y. Decyzja: odrzucamy je i eksportujemy
  lokalną kopię jako „odzyskany szkic", zamiast udawać scalanie. To jest failure mode do README
- Symulator sieci: middleware transportu z suwakami latency / jitter / packet loss / partition.
  Ten sam kod napędza demo (idea.md) i testy chaosu (CP8) — jedna rzecz, dwa zastosowania

**Exit criteria:** wyłączenie sieci na 60 s przy aktywnym pisaniu po obu stronach → po powrocie
oba klienty mają identyczny tekst zawierający obie edycje.

---

### CP7 — Observability (1,5 dnia)

**Deliverables:**
- OpenTelemetry: trace pojedynczej edycji end-to-end. `traceparent` (W3C) przenoszony
  w headerze STOMP; spany: `edit.submit` (browser) → `op.receive` → `crdt.merge` →
  `op.persist` → `op.broadcast` → span **links** do N × `op.deliver` (fan-out to nie jest
  relacja rodzic-dziecko — link jest tu semantycznie poprawny)
- Metryki (Micrometer → Prometheus):
  `doc_active_sessions`, `crdt_merge_duration_seconds`, `op_propagation_latency_seconds`,
  `crdt_concurrent_merges_total`, `doc_tombstone_ratio`, `ws_reconnect_total`,
  `offline_buffer_size`, `redis_gap_repairs_total`
- **Metodologia pomiaru latencji propagacji** (bo inaczej to liczba bez znaczenia):
  - *serwerowa*: ingest → broadcast, jeden zegar, uczciwa
  - *end-to-end*: mierzona wyłącznie w harnessie benchmarkowym, gdzie writer i reader są
    w jednym procesie i dzielą zegar monotoniczny
  - Nie mieszamy tych dwóch liczb w README i piszemy wprost, która jest która
- Grafana: dashboard provisionowany z repo (JSON), nie klikany ręcznie
- Exemplars: histogram Prometheusa → konkretny trace (opcjonalne, jeśli zostanie czas)

**Exit criteria:** jedno naciśnięcie klawisza w przeglądarce = jeden trace widoczny end-to-end;
dashboard startuje z `docker compose up` bez ręcznej konfiguracji.

---

### CP8 — Chaos + benchmarki (1,5–2 dni)

**Deliverables:**
- Testy chaosu: losowe opóźnienia, reorder, drop, partycja sieciowa, kill instancji backendu
  w trakcie edycji. Asercja końcowa zawsze ta sama: **wszystkie repliki zbieżne**
- Toxiproxy w Testcontainers dla chaosu na prawdziwym połączeniu (nie tylko in-process)
- **Gatling** (nie k6) — bo JVM, zgodny ze stackiem, łatwiej ręcznie składać ramki STOMP
- Scenariusze: 10 / 50 / 100 / 250 userów w jednym dokumencie; dokument 1k / 10k / 100k / 1M znaków
- Do README, z metodologią i specyfikacją maszyny:
  - throughput (ops/s) vs liczba userów
  - latencja propagacji p50/p95/p99 vs liczba userów
  - **punkt degradacji** — przy ilu userach / jakim rozmiarze dokumentu p95 przekracza próg
  - zużycie pamięci vs rozmiar dokumentu (koszt tombstone'ów, przed i po GC)

**Exit criteria:** README zawiera tabele z liczbami i wykresy, zero przymiotników typu „szybki".
Jeśli coś degraduje się źle — zapisujemy to szczerze, wraz z hipotezą dlaczego.

---

### CP9 — Dokumentacja finalna + demo (1–1,5 dnia)

**Deliverables:**
- **GIF na samej górze README, przed jakimkolwiek tekstem.** Nagrywany skryptem Playwright
  (dwa konteksty przeglądarki, deterministyczny scenariusz) → wideo → `ffmpeg` + `gifski`.
  Skrypt w repo → demo da się nagrać ponownie po zmianie UI, i przy okazji jest testem E2E
- Scenariusz demo: (1) dwóch userów pisze w tym samym zdaniu, (2) rozłączenie + edycja offline
  + reconnect i scalenie, (3) cofnięcie do wcześniejszej wersji
- Diagramy: architektura, sekwencja pojedynczej edycji, maszyna stanów reconnectu (Mermaid)
- **Sekcja Failure modes** (CLAUDE.md §6): utrata połączenia, pad instancji/failover,
  jednoczesna edycja tego samego znaku, bardzo duży dokument i GC tombstone'ów
- **Sekcja „co zrobiłbym inaczej w produkcji"** (§7): brak rich text, uproszczony auth,
  brak shardingu dokumentów, brak rate limitingu per user, anomalia przeplotu RGA,
  podwójna implementacja algorytmu jako dług — i jak każdą z tych rzeczy zaadresować przy skali
- Przejście po ADR-ach: dopisanie „zweryfikowano w fazie X" tam, gdzie w CP1 była niepewność

---

## 4. Decyzje podjęte (2026-08-14)

1. **Głębokość CRDT** → samo **RGA**, z anomalią przeplotu udokumentowaną jako znane
   ograniczenie (jawnie oznaczony test + sekcja w ADR-001). Fugue jako drugi backend
   algorytmu **odrzucone** ze względu na koszt (+2–3 dni); wchodzi do sekcji
   „co zrobiłbym inaczej w produkcji" jako świadomie niewykonany krok.
2. **Warstwa edytora** → **CodeMirror 6** jako czysta warstwa widoku (model dokumentu
   to nasz CRDT, CM6 renderuje i dostarcza dekoracje dla zdalnych kursorów). Uzasadnienie
   trafia do ADR-006.
3. **Zakres benchmarku** → do **250 userów / 1M znaków**, jedna maszyna, specyfikacja
   sprzętu podana w README razem z wynikami. Bez distributed Gatling.

## 5. Sumaryczna estymata

| Checkpoint | Dni |
|---|---|
| CP1 ADR-y + szkielet | 0,5–1 |
| CP2 CRDT + property testy | 2–3 |
| CP3 WebSocket + persystencja | 2 |
| CP4 Frontend + kursory | 2–3 |
| CP5 Historia zmian | 1–1,5 |
| CP6 Offline / reconnect | 1,5 |
| CP7 Observability | 1,5 |
| CP8 Chaos + benchmarki | 1,5–2 |
| CP9 Dokumentacja + demo | 1–1,5 |
| **Razem** | **~13–17 dni roboczych** |

Gdyby zabrakło czasu: tniemy CP5 (historia) do minimum i rozszerzenia CRDT z §4.1.
**Nie tniemy CP7 (observability)** — CLAUDE.md §4 mówi wprost, że to jeden z głównych
sygnałów senioralności.
