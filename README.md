# Collaborative Document Editor

> **Stan projektu: w budowie (CP1–CP3 z 9 ukończone).**
> README jest pisane równolegle z kodem, sekcja po sekcji — nie na końcu. Sekcje
> oznaczone `⏳` czekają na checkpoint, który dostarczy do nich dane. Nie ma tu
> zdań o wydajności bez liczby ani deklaracji o funkcji, która jeszcze nie działa.

<!-- CP9: tutaj trafia GIF demo, PRZED jakimkolwiek tekstem. -->

Edytor tekstu czasu rzeczywistego: wielu użytkowników edytuje ten sam dokument jednocześnie,
bez konfliktów i bez utraty zmian — również przy słabym łączu, przy jednoczesnej edycji tego
samego fragmentu i po rozłączeniu klienta.

Algorytm scalania (CRDT typu RGA) jest **napisany od zera**, w Javie po stronie serwera
i w TypeScripcie po stronie klienta. Nie jest to opakowanie na Yjs ani Automerge — powód
i porównanie z gotowymi rozwiązaniami są w [ADR-0001](docs/adr/0001-crdt-vs-ot.md).

---

## Co tu jest ciekawego

Jeśli masz trzy minuty, to trzy rzeczy warte spojrzenia:

1. **[ADR-0001](docs/adr/0001-crdt-vs-ot.md) — wybór CRDT vs OT**, wraz z jawnie udokumentowaną
   **słabością wybranego algorytmu** (anomalia przeplotu w RGA) i testem, który tę słabość
   demonstruje zamiast ją ukrywać.
2. **[ADR-0004](docs/adr/0004-skalowanie-poziome.md) — każda instancja backendu jest repliką CRDT.**
   Bez lidera, bez rozproszonego locka, bez sticky sessions. Redis pub/sub jest zawodną ścieżką
   szybką, a log operacji w Postgresie ścieżką autorytatywną — dzięki czemu zgubiona wiadomość
   jest problemem latencji, a nie poprawności.
3. **[Property testy w `crdt-core`](crdt-core/src/test/java/dev/collabedit/crdt/)** — losowe
   sekwencje operacji dostarczane w losowej kolejności muszą zawsze zbiec się do tego samego
   stanu. To jest dowód poprawności rdzenia, a nie zestaw przykładów — i jest sprawdzone,
   że te testy faktycznie łapią uszkodzony algorytm, a nie tylko przechodzą.

---

## Architektura

```
┌─ frontend (React + TypeScript, Vite) ───────────────────────┐
│  CodeMirror 6 — WYŁĄCZNIE warstwa widoku (ADR-0006)          │
│  crdt-ts/     — własna implementacja RGA                     │
│  transport/   — STOMP + bufor offline + symulator sieci      │
└──────────────────────────┬──────────────────────────────────┘
                           │  WebSocket / STOMP
                           │  (traceparent w nagłówku ramki)
┌──────────────────────────▼──────────────────────────────────┐
│ app — Spring Boot 3, Java 21                                 │
│   ws/        STOMP, presence                                 │
│   domain/    replika dokumentu w pamięci                     │
│   persist/   log operacji + snapshoty                        │
│   history/   time travel                                     │
│   cluster/   Redis pub/sub + anti-entropy                    │
└────────┬─────────────────────────┬──────────────────────────┘
         │                         │
    PostgreSQL                  Redis
  (źródło prawdy)        (zawodna ścieżka szybka)

crdt-core/ — osobny moduł Mavena, ZERO zależności od Springa.
             Algorytm + property testy + JMH.
```

Podział na `crdt-core` bez Springa jest celowy: algorytm da się testować i benchmarkować
w izolacji, a kompilator pilnuje, żeby warstwa aplikacyjna nie wyciekła do rdzenia.

---

## Decyzje architektoniczne

Wszystkie w [`docs/adr/`](docs/adr/). Każdy ADR ma sekcję **Konsekwencje negatywne**, która
nigdy nie jest pusta, oraz — tam, gdzie czegoś jeszcze nie wiem — jawne
*„do zweryfikowania w CP-X"* zamiast udawanej pewności.

| ADR | Decyzja |
|---|---|
| [0001](docs/adr/0001-crdt-vs-ot.md) | RGA (CRDT) zamiast OT — i dlaczego nie Logoot, nie Fugue, nie Yjs |
| [0002](docs/adr/0002-transport.md) | WebSocket + STOMP; uzasadnienie każdej wartości heartbeatu i limitu bufora |
| [0003](docs/adr/0003-historia-zmian.md) | Log operacji jako źródło prawdy, snapshoty jako usuwalny cache |
| [0004](docs/adr/0004-skalowanie-poziome.md) | Instancja backendu jako replika CRDT + anti-entropy |
| [0005](docs/adr/0005-tombstone-gc.md) | GC tombstone'ów przez stabilność przyczynową |
| [0006](docs/adr/0006-warstwa-edytora.md) | CodeMirror 6 jako widok; model dokumentu własny |
| [0007](docs/adr/0007-zakres-ui.md) | Język wizualny mockupu przyjęty, jego zakres funkcjonalny odrzucony |

Plan pracy i checkpointy: [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md).

---

## Uruchomienie

**Wymagania:** JDK 21, Node 22.12+, Docker.

<details>
<summary>Jeśli używasz colimy zamiast Docker Desktop (rozwinąć)</summary>

Testcontainers nie czyta kontekstu dockera, a Docker 29+ odrzuca API w wersji, którą
domyślnie negocjuje docker-java. Potrzebne są trzy rzeczy — każda naprawia inny objaw
tego samego problemu, wszystkie mylące, bo brzmią jak „brak Dockera":

```bash
# 1. wskazanie gniazda (inaczej: "Could not find a valid Docker environment")
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock

# 2. ścieżka gniazda WEWNĄTRZ maszyny wirtualnej — dla kontenera-sprzątacza Ryuk
#    (inaczej: "error while creating mount source path ... operation not supported")
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock

# 3. wersja API (inaczej: "client version 1.32 is too old")
echo "api.version=1.44" > ~/.docker-java.properties
```

Na runnerach GitHuba nic z tego nie jest potrzebne — Docker jest tam natywny.
</details>

```bash
# Zależności infrastrukturalne
docker compose up -d postgres redis

# Backend
./mvnw -pl app spring-boot:run

# Frontend
cd frontend && npm install && npm run dev

# Stack obserwowalności (CP7) — Prometheus, Tempo, Grafana na :3001
docker compose --profile observability up -d
```

Projekt **nie dziedziczy po `spring-boot-starter-parent`**. To celowe: gdyby dziedziczył,
moduł `crdt-core` — który z założenia nie ma nic wspólnego ze Springiem ([ADR-0001](docs/adr/0001-crdt-vs-ot.md)) —
dostawałby jego zarządzanie zależnościami i konwencje, a deklarowana granica architektoniczna
byłaby tylko deklaracją. Zamiast tego `spring-boot-dependencies` jest importowany jako BOM,
więc wersje są spójne, ale zależność idzie w jedną stronę i dotyczy wyłącznie modułu `app`.

---

## Testy

```bash
./mvnw -pl crdt-core test              # property testy (stałe ziarno, determinizm)
./mvnw -pl crdt-core -Pthorough test   # 10 000 prób — jak w nocnym CI
./mvnw install                         # wszystko, w tym integracyjne na prawdziwym Postgresie
./mvnw -pl crdt-core test-compile exec:exec@run-benchmarks   # benchmarki JMH
```

Testy są uporządkowane wg tego, jaki failure mode wyłapują, a nie wg pokrycia.

| Test | Jaki realny failure mode wyłapuje |
|---|---|
| `convergence` | Brak przemienności scalania — ciche gubienie zmian, gdy dwie osoby piszą jednocześnie |
| `convergenceWithInterleavedDelivery` | Błędy widoczne tylko wtedy, gdy replika pisze na stanie już scalonym z cudzymi zmianami |
| `noOperationStaysPending` | Operacja utknęła w buforze na zawsze — repliki zgodnie *nie mają* zmiany, więc sam test zbieżności tego nie widzi |
| `idempotence` | Retransmisja po reconnect albo powtórka z Redisa dubluje tekst |
| `deliveryOrderDoesNotMatter` | Algorytm deterministyczny, ale zależny od kolejności — działa na jednym serwerze, psuje się przy skalowaniu |
| `singleReplicaBehavesLikeStringBuilder` | Błąd w mapowaniu pozycji na identyfikator; wszystkie repliki mylą się tak samo i zbiegają do wspólnego, ale **błędnego** tekstu |
| `rgaLinearizationInvariantHolds` | Jw. — patrzy na strukturę samą w sobie, więc wspólny błąd go nie oszuka |
| `sequentialTypingCompressesIntoFewBlocks` | Kompresja run-length nigdy nie zachodzi; poprawność cała, ale dokument 100 tys. znaków to znów 100 tys. obiektów |
| `naiveAndIndexedProduceIdenticalDocuments` | Błąd w agregatach treapa — najczęściej brak aktualizacji po oznaczeniu tombstone'em |
| `bothImplementationsAnswerQueriesIdentically` | Zła pozycja z drzewa po rotacji: tekst poprawny, ale kursor użytkownika ląduje gdzie indziej |
| `gcPreservesText` | GC kasuje węzeł będący czyimś originem — struktura pada dopiero przy następnej edycji w to miejsce |
| `backwardTypingInterleaves_knownLimitation` | Nie wyłapuje błędu — **utrwala znane ograniczenie RGA** ([ADR-0001](docs/adr/0001-crdt-vs-ot.md)) |

**Czy te testy mają zęby — sprawdzone, nie założone.** Odwrócenie warunku skanu RGA
w kodzie produkcyjnym wywala 9 z 13 testów. Usunięcie aktualizacji agregatów w treapie
wywala dokładnie te dwa, które to opisują. Testy, których nie próbowano złamać,
są deklaracją, a nie zabezpieczeniem — [notatka o tym, jak jedna taka mutacja
ujawniła realną dziurę w zestawie](docs/crdt-design.md#czego-to-nauczyło-o-testach).

---

## Wydajność ⏳ *(CP8)*

Sekcja zostanie wypełniona liczbami z metodologią pomiaru i specyfikacją maszyny:
throughput, latencja propagacji p50/p95/p99 w funkcji liczby użytkowników, punkt degradacji
przy rosnącym rozmiarze dokumentu, narzut pamięciowy tombstone'ów przed i po GC.

Uwaga metodologiczna, którą warto zapisać zawczasu: **„latencja propagacji" to dwie różne
liczby** — czas serwerowy (przyjęcie → rozesłanie, jeden zegar) i czas end-to-end (klient →
klient, dwa zegary). Ta druga jest mierzona wyłącznie w harnessie benchmarkowym, gdzie
nadawca i odbiorca dzielą jeden zegar monotoniczny. Nie będą tu mieszane.

---

## Failure modes ⏳ *(CP9, uzupełniane na bieżąco)*

Co dokładnie dzieje się, gdy coś pójdzie nie tak. Część jest już rozstrzygnięta w ADR-ach:

| Scenariusz | Zachowanie | Źródło decyzji |
|---|---|---|
| Utrata połączenia w trakcie edycji | Edycja lokalna działa dalej, operacje trafiają do bufora; po powrocie wymiana version vectorów i scalenie | ⏳ CP6 |
| Pad instancji między rozesłaniem a zapisem operacji | Operacja jest u innych klientów, ale nie w bazie → klient retransmituje ją po reconnect, bo bufor zwalnia dopiero po `ack` potwierdzającym **zapis**, nie rozesłanie | [ADR-0003](docs/adr/0003-historia-zmian.md) |
| Zgubiona wiadomość Redis pub/sub | Wykrycie luki w `server_seq` → dociągnięcie z Postgresa. W najgorszym przypadku (zgubiona ostatnia operacja + cisza) rozjazd trwa do 30 s, do sweepu anti-entropy | [ADR-0004](docs/adr/0004-skalowanie-poziome.md) |
| Dwóch userów edytuje ten sam znak | Obie operacje zachowane, deterministyczny porządek wg `(counter, replicaId)`; brak arbitra | [ADR-0001](docs/adr/0001-crdt-vs-ot.md) |
| Klient offline dłużej niż okno GC (7 dni) | Operacje **odrzucone jawnie** (`RESYNC_REQUIRED`), lokalna wersja zapisana jako osobny dokument „Odzyskany szkic". Świadomie nie udajemy scalenia, bo informacja o pozycji znaków została utracona | [ADR-0005](docs/adr/0005-tombstone-gc.md) |
| Bardzo duży dokument | ⏳ CP8 — punkt degradacji do zmierzenia | |

---

## Co zrobiłbym inaczej w produkcji ⏳ *(CP9)*

Lista świadomych uproszczeń narasta w trakcie pracy, żeby nie odtwarzać jej z pamięci
na końcu. Stan na teraz:

- **Brak formatowania tekstu (rich text)** — zakres celowo zawężony do zwykłego tekstu,
  żeby zejść głębiej w problem rdzeniowy zamiast szerzej (`idea.md`, sekcja Ryzyka).
- **Fugue odrzucone na rzecz RGA** — RGA ma udokumentowaną anomalię przeplotu, Fugue jej
  nie ma. Powód odrzucenia był budżetowy, nie techniczny ([ADR-0001](docs/adr/0001-crdt-vs-ot.md)).
- **Algorytm istnieje w dwóch implementacjach** (Java + TypeScript). Mitygacja przez korpus
  wektorów konformancyjnych w CI, ale koszt każdej zmiany w algorytmie jest podwojony.
- **Okno GC 7 dni to wartość zgadnięta**, nie wyliczona — w produkcji dobrałbym ją
  z telemetrii długości sesji offline ([ADR-0005](docs/adr/0005-tombstone-gc.md)).
- **GC odzyskuje mniej, niż zakładałem.** Tombstone w środku tekstu jest originem
  następnego bloku, więc nie wolno go usunąć niezależnie od stabilności przyczynowej —
  kolekcjonowalny jest praktycznie tylko ogon dokumentu. Właściwą odpowiedzią jest
  porzucanie **treści** skasowanych węzłów przy zachowaniu ich w strukturze (tak robi Yjs);
  wymaga to zmiany reprezentacji bloku i unieważnia format snapshotu, więc nie mieści się
  w zakresie. Rozwinięcie w [ADR-0005](docs/adr/0005-tombstone-gc.md#faktyczny-zasięg-gc--zweryfikowane-w-cp6-wynik-gorszy-od-zakładanego).
- **Redis bez persystencji, log operacji rośnie bez ograniczeń** — w produkcji potrzebna
  archiwizacja starych segmentów logu i kompakcja ([ADR-0003](docs/adr/0003-historia-zmian.md)).
- **Brak SockJS fallback** — klient za proxy blokującym `Upgrade` nie połączy się wcale
  ([ADR-0002](docs/adr/0002-transport.md)).
- ⏳ dalsze pozycje dochodzą wraz z kolejnymi checkpointami.

---

## Struktura repozytorium

```
crdt-core/     algorytm RGA + property testy + JMH  (zero Springa)
app/           Spring Boot: WebSocket, persystencja, historia, cluster
frontend/      React + TypeScript + CodeMirror 6
conformance/   wektory testowe wspólne dla implementacji w Javie i w TypeScripcie
docs/adr/      decyzje architektoniczne
docs/design/   mockup wizualny, na którym oparty jest interfejs
ops/           konfiguracje Prometheus / Tempo / Grafana / OTel Collector
```
