# Collaborative Document Editor

![Demo: dwie osoby edytują jednocześnie, jedna traci sieć, zmiany scalają się po powrocie, wersja zostaje cofnięta](docs/demo.gif)

*Dwie przeglądarki, jeden dokument. Współbieżna edycja tego samego akapitu, praca po odcięciu
sieci, scalenie po powrocie łączności i cofnięcie do wcześniejszej wersji — bez konfliktu
i bez utraty ani jednego znaku. Nagranie powstaje ze
[skryptu](frontend/e2e/demo.spec.ts), nie z ręcznego klikania, więc pokazuje dokładnie to,
co sprawdza CI.*

Edytor tekstu czasu rzeczywistego: wielu użytkowników edytuje ten sam dokument jednocześnie,
bez konfliktów i bez utraty zmian — również przy słabym łączu, przy jednoczesnej edycji tego
samego fragmentu i po rozłączeniu klienta.

Algorytm scalania (CRDT typu RGA) jest **napisany od zera**, w Javie po stronie serwera
i w TypeScripcie po stronie klienta. Nie jest to opakowanie na Yjs ani Automerge — powód
i porównanie z gotowymi rozwiązaniami są w [ADR-0001](docs/adr/0001-crdt-vs-ot.md).

---

## Co tu jest ciekawego

Jeśli masz trzy minuty:

1. **[Testy zostały sprawdzone pod kątem tego, czy w ogóle łapią błędy](docs/crdt-design.md#czego-to-nauczyło-o-testach).**
   Wprowadziłem mutację do kodu produkcyjnego i cały zestaw property testów przeszedł — bo
   wszystkie scenariusze miały jedną, wspólną lukę. Przy okazji **wycofałem z dokumentacji
   tezę, której nie udało mi się potwierdzić**: 200 tys. losowych scenariuszy pokazało,
   że algorytm zbiega się również bez warunku, który uważałem za konieczny.
2. **[ADR-0004](docs/adr/0004-skalowanie-poziome.md) — każda instancja backendu jest repliką
   CRDT.** Bez lidera, bez rozproszonego locka, bez sticky sessions. Redis to zawodna ścieżka
   szybka, log operacji w Postgresie — autorytatywna. Zgubiona wiadomość jest przez to
   problemem latencji, a nie poprawności; [test chaosu](app/src/test/java/dev/collabedit/app/ClusterChaosIT.java)
   zatrzymuje Redisa w trakcie pisania i sprawdza, że tak faktycznie jest.
3. **[Otwarte pytanie o wydajność okazało się wadą mojego stanowiska pomiarowego,
   nie systemu.](#punkt-degradacji--rozstrzygnięty-i-to-nie-na-korzyść-mojego-stanowiska)**
   Przez cały CP8 „powyżej ~10 klientów zbieżność przestaje być powtarzalna" wyglądało jak
   granica systemu. Był to domyślny bufor 8 KiB w kliencie testowym, który przepuszczał
   rozgłoszenia, a **zatrzymywał wyłącznie odpowiedzi na resynchronizację** — czyli jedyny
   mechanizm zdolny naprawić zgubioną operację. Treap jest przy okazji 210× szybszy
   od skanowania liniowego przy 100 tys. znaków — i 1,4× **wolniejszy** przy odczycie całości.

Cztery błędy w tym projekcie znalazły testy, a nie przegląd kodu: dublowanie dokumentu przy
otwarciu, snapshot niezapisywalny powyżej 64 KB, odpowiedź na resynchronizację przekraczająca
limit ramki i nazwa użytkownika ze znakiem `|` unieważniająca własny token. Wszystkie są
opisane w commitach i w komentarzach w miejscu naprawy.

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
| [0007](docs/adr/0007-zakres-ui.md) | Język wizualny mockupu przyjęty; diagnostyka usunięta z interfejsu użytkownika (decyzja zrewidowana) |

Diagramy (architektura, droga pojedynczej edycji, stany połączenia):
[`docs/architektura.md`](docs/architektura.md).
Szczegóły implementacyjne algorytmu: [`docs/crdt-design.md`](docs/crdt-design.md).
Plan pracy i checkpointy: [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md).

---

## Uruchomienie

### Najszybciej: sam Docker

```bash
docker compose --profile app up -d     # → http://localhost:5173
```

Buduje backend i frontend z tego repozytorium i podnosi całość razem z bazą i Redisem.
Nie wymaga JDK ani Node'a. Pierwsze uruchomienie trwa kilka minut (Maven ściąga zależności),
kolejne są natychmiastowe.

### Do pracy nad kodem

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
./mvnw -pl crdt-core exec:exec@run-benchmarks                # benchmarki JMH (~7 min)
./mvnw -pl app test -Dtest=LoadHarnessIT -Dbenchmark=true    # harness obciążeniowy (~10 min)
./mvnw -pl app test -Dtest=BroadcastFanoutIT -Dfanout=true   # ilu klientów zbiega się POWTARZALNIE
cd frontend && npm run e2e                                   # dwie przeglądarki (wymaga działającego stacku)
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

## Wydajność

Wszystkie liczby pochodzą z jednej maszyny: **MacBook, Apple Silicon (aarch64), 10 rdzeni,
JDK 25-ea, 4 GB sterty JVM**, Postgres i Redis w kontenerach na tej samej maszynie.
Liczba bez maszyny jest bezużyteczna, więc podaję ją przy każdej tabeli.

### Struktura indeksująca: O(n) kontra O(log n)

Benchmark JMH (`./mvnw -pl crdt-core exec:exec@run-benchmarks`), średni czas operacji
w mikrosekundach, 3 iteracje rozgrzewki + 5 pomiarowych po 2 s.

| Operacja | Dokument | `ArrayBlockList` | `TreapBlockList` | Różnica |
|---|---:|---:|---:|---:|
| Pozycja w tekście → identyfikator | 1 000 | 0,367 µs | 0,065 µs | 5,6× |
| | 10 000 | 5,510 µs | 0,149 µs | 37× |
| | 100 000 | **60,2 µs** | **0,286 µs** | **210×** |
| Identyfikator → pozycja | 1 000 | 0,187 µs | 0,068 µs | 2,7× |
| | 10 000 | 2,863 µs | 0,122 µs | 23× |
| | 100 000 | **40,3 µs** | **0,210 µs** | **192×** |
| Pełne wstawienie | 100 000 | 684 µs | 4,66 µs | 147× |
| Odczyt całego tekstu | 100 000 | 310 µs | 429 µs | **0,72× (treap WOLNIEJSZY)** |

Kształt jest ważniejszy od pojedynczych liczb. Przy dziesięciokrotnym wzroście dokumentu
wyszukiwanie w wersji naiwnej rośnie **15× i 11×** (liniowo), a w treapie **2,3× i 1,9×**
(logarytmicznie). To jest dokładnie ta różnica, dla której warto było napisać drugą
implementację — i którą widać dopiero po zmierzeniu, a nie po przeczytaniu złożoności.

**Odczyt całego tekstu jest w treapie wolniejszy o 40%** i nie jest to błąd pomiaru:
przejście drzewa ma gorszą lokalność pamięci niż iteracja po tablicy. Ta operacja wykonuje
się raz przy otwarciu dokumentu, a wyszukiwanie — na każde naciśnięcie klawisza, więc
kompromis jest oczywisty. Ale jest to kompromis, a nie darmowa poprawa, i zamiatanie
go pod dywan byłoby nieuczciwe wobec liczb.

### Propagacja zmian między klientami

Harness własny, nie Gatling — uzasadnienie w `LoadHarnessIT`: interesująca wielkość to
odstęp między zdarzeniami u **dwóch różnych klientów**, więc oba końce muszą dzielić jeden
zegar. Klienci i serwer działają w jednym procesie, latencja sieci ≈ 0; liczby opisują
koszt systemu, nie doświadczenie użytkownika przez internet.

Każdy klient wykonuje 40 wstawień **na początek dokumentu** (nie na koniec — pisanie ciągiem
kompresuje się do jednego bloku i omija ścieżkę, która faktycznie skaluje się z rozmiarem).

| Dokument | Klienci | p50 | p95 | p99 | Zbieżność |
|---:|---:|---:|---:|---:|---|
| pusty | 2 | 32 ms | 33 ms | 34 ms | 53 ms |
| pusty | 10 | 86 ms | 93 ms | 94 ms | 117 ms |
| pusty | 25 | 92 ms | 126 ms | 130 ms | 188 ms |
| pusty | 50 | 226 ms | 389 ms | 420 ms | 397 ms |
| 10 000 znaków | 2 | 3,7 ms | 4,6 ms | 4,7 ms | 51 ms |
| 10 000 znaków | 10 | 15 ms | 19 ms | 20 ms | 56 ms |
| 10 000 znaków | 25 | 73 ms | 90 ms | 92 ms | 131 ms |
| 10 000 znaków | 50 | 128 ms | 182 ms | 192 ms | 237 ms |
| 100 000 znaków | 2 | 11 ms | 11 ms | 11 ms | 50 ms |
| 100 000 znaków | 10 | 15 ms | 16 ms | 17 ms | 61 ms |
| 100 000 znaków | 25 | 57 ms | 65 ms | 67 ms | 116 ms |
| 100 000 znaków | 50 | 127 ms | 193 ms | 203 ms | 239 ms |

**Rozmiar dokumentu praktycznie nie wpływa na latencję** — 100 tys. znaków wypada nie gorzej
niż dokument pusty. To potwierdza, że po wymianie indeksu na treap wąskim gardłem przestała
być struktura danych.

### Co dało sklejanie rozgłoszeń

Do CP10 każda operacja szła w osobnej ramce **do każdego odbiorcy**, więc liczba ramek rosła
z kwadratem liczby klientów. Od CP10 operacje są zbierane w okno 10 ms.

| Dokument | Klienci | p50 przed | p50 po | Zbieżność przed | po |
|---:|---:|---:|---:|---:|---:|
| 10 000 znaków | 25 | 191 ms | **73 ms** | 290 ms | **131 ms** |
| 100 000 znaków | 50 | 553 ms | **127 ms** | 871 ms | **239 ms** |

Zysk rośnie z liczbą klientów — czyli dokładnie tam, gdzie był problem. Przy dwóch klientach
sklejać nie ma czego i widać to w liczbach (32 ms wobec 22 ms, czyli nieco gorzej: doszło
okno oczekiwania, a nie ma z czego go zamortyzować).

### Punkt degradacji — rozstrzygnięty, i to nie na korzyść mojego stanowiska

Poprzednia wersja tego README kończyła się tutaj zdaniem „nie wiem, czy przyczyna leży
w systemie, czy w stanowisku pomiarowym". **Leżała w stanowisku.**

`TestStompClient` budował `StandardWebSocketClient` bez konfiguracji kontenera JSR-356,
którego domyślny bufor tekstowy to **8 KiB** — ośmiokrotnie mniej niż limit ramki, który
ten system deklaruje ([ADR-0002](docs/adr/0002-transport.md)). Każda większa wiadomość
po prostu do klienta testowego nie docierała.

Dlaczego objawiało się to jako *nieodwracalny* rozjazd, a nie jako zwykłe zgubienie ramki:

1. Rozgłoszenia szły po jednej operacji na ramkę (~70 B), więc **próg był nieosiągalny**
   i mechanizm wyglądał na sprawny.
2. Gdy jedna operacja jednak zginęła, klient poprawnie wykrywał lukę i prosił o resynchronizację.
3. Odpowiedź na resynchronizację jest dzielona na porcje po **300 operacji** (poprawka z CP8),
   a to jest ok. **15 KB** — czyli powyżej 8 KiB. **Mechanizm ratunkowy był jedyną rzeczą,
   której bufor nie przepuszczał.**

Stąd dokładnie ten objaw, który był w README: serwer z kompletem operacji, część klientów
trwale kilka operacji w tyle, brak błędu w logu. Naprawa polegająca na skróceniu porcji
resynchronizacji „naprawiłaby" to na stanowisku i nic nie zmieniła w systemie.

Po podniesieniu bufora stanowiska **wszystkie 12 scenariuszy zbiega się**, a `BroadcastFanoutIT`
daje 3/3 powtarzalnych zbieżności dla 2, 10, 25 i 50 klientów — również na kodzie *sprzed*
sklejania rozgłoszeń. Sklejanie uzasadnia się więc **latencją, a nie naprawą zbieżności**,
i tak jest opisane wyżej.

Czego to nie dowodzi: klienci i serwer nadal działają w jednym procesie, więc liczby opisują
koszt systemu, nie doświadczenie przez internet. Rozdzielenie na osobne maszyny pozostaje
niezrobione — ale nie jest już potrzebne do odpowiedzi na pytanie „system czy stanowisko".

---

## Failure modes

Co dokładnie dzieje się, gdy coś pójdzie nie tak. Każdy wiersz ma źródło decyzji, a te
oznaczone jako zweryfikowane mają test, który to sprawdza.

| Scenariusz | Zachowanie | Zweryfikowane |
|---|---|---|
| **Utrata połączenia w trakcie edycji** | Edycja lokalna działa dalej. Operacje trafiają do bufora w IndexedDB i przeżywają zamknięcie karty. Po powrocie klient wymienia kursor z serwerem i dosyła zaległości. | `e2e: odcięcie sieci`, `e2e: bufor trwały` |
| **Pad instancji między rozgłoszeniem a zapisem** | Operacja jest u innych klientów, ale nie w bazie. Klient retransmituje ją po ponownym połączeniu, bo bufor zwalnia dopiero po potwierdzeniu **zapisu**, nie rozgłoszenia. | [ADR-0003](docs/adr/0003-historia-zmian.md) |
| **Utrata repliki z pamięci / restart instancji** | Stan odtwarzany z logu operacji: najbliższy snapshot + ogon. Skasowanie wszystkich snapshotów nie zmienia wyniku, tylko czas. | `stateSurvivesReplicaLoss`, `snapshotsAreOnlyACache` |
| **Awaria Redisa w trakcie edycji** | Edycja i zapis działają dalej — Redis jest ścieżką szybką, nie krytyczną. Instancje rozjeżdżają się na czas awarii, a przegląd anti-entropy dociąga różnicę z logu. | `redisOutageIsRepairedByAntiEntropy` |
| **Zgubione rozgłoszenie do klienta** | Klient wykrywa lukę po potwierdzeniu operacji, której u siebie nie ma, i sam prosi o resynchronizację. Kursor `server_seq` by tu nie wystarczył — rośnie od potwierdzeń, więc byłby „na bieżąco" mimo braku. | wykryte i naprawione w CP8 |
| **Dwoje ludzi edytuje ten sam znak** | Obie zmiany zachowane, deterministyczny porządek wg `(lamport, replicaId)`, bez arbitra. | `concurrentEditOfSameCharacterKeepsBoth`, property `convergence` |
| **Klient offline dłużej niż okno GC (7 dni)** | Operacje **odrzucone jawnie** (`RESYNC_REQUIRED`), lokalna wersja zapisana jako osobny dokument „Odzyskany szkic". Świadomie nie udajemy scalenia — informacja o pozycji tych znaków została utracona. | `staleOperationIsRejectedExplicitly` |
| **Operacja wyprzedza swoją przyczynę** | Trafia do bufora i jest ponawiana po każdym udanym scaleniu. NIE jest mylona z sytuacją powyżej — warunek odmowy wymaga jednocześnie braku originu i pozycji poniżej granicy GC. | `operationAheadOfItsCauseIsNotRejected` |
| **Bardzo duży dokument** | Rozmiar praktycznie nie wpływa na latencję (100 tys. znaków ≈ dokument pusty) po wymianie indeksu na treap. Tombstone'y rosną z liczbą edycji, a GC odzyskuje mniej, niż zakładałem. | sekcja [Wydajność](#wydajność), [ADR-0005](docs/adr/0005-tombstone-gc.md) |
| **50 klientów w jednym dokumencie** | Zbiega się powtarzalnie (3/3 przebiegi, 397 ms przy pustym dokumencie, 239 ms przy 100 tys. znaków). Wcześniejsza obserwacja „powyżej ~10 klientów zbieżność przestaje być powtarzalna" okazała się **wadą stanowiska pomiarowego, nie systemu** — patrz [Punkt degradacji](#punkt-degradacji--rozstrzygnięty-i-to-nie-na-korzyść-mojego-stanowiska). | `BroadcastFanoutIT`, harness CP10 |

---

## Co zrobiłbym inaczej w produkcji

Lista świadomych uproszczeń i znanych granic. Powstawała **w trakcie** pracy, a nie
z pamięci na końcu — dlatego zawiera też rzeczy, które wyszły dopiero przy implementacji
i okazały się gorsze, niż zakładałem.

**Algorytm**
- **Fugue zamiast RGA.** RGA ma udokumentowaną anomalię przeplotu: pisanie wstecz przez
  dwie osoby daje przeplecione litery ([test, który to utrwala](crdt-core/src/test/java/dev/collabedit/crdt/RgaInterleavingTest.java)).
  Fugue tego nie ma. Odrzucone z powodu budżetu, nie technicznego.
- **GC odzyskuje mniej, niż zakłada [ADR-0005](docs/adr/0005-tombstone-gc.md).** Tombstone
  w środku tekstu jest originem następnego bloku, więc nie wolno go usunąć niezależnie od
  stabilności przyczynowej — kolekcjonowalny jest praktycznie tylko ogon dokumentu. Właściwą
  odpowiedzią jest porzucanie **treści** skasowanych węzłów przy zachowaniu ich w strukturze
  (tak robi Yjs); wymaga zmiany reprezentacji bloku i unieważnia format snapshotu.
- **Dwie implementacje jednego algorytmu** (Java + TypeScript) to dług: każda zmiana kosztuje
  podwójnie. Korpus wektorów konformancyjnych chroni przed cichym rozjazdem, ale nie przed
  kosztem. W produkcji: jedna implementacja skompilowana do WebAssembly.
- **Indeks po stronie klienta jest naiwny (O(n)).** Po stronie serwera treap wszedł po
  zmierzeniu; odpowiedniego pomiaru w przeglądarce nie zrobiłem, więc nie optymalizowałem
  w ciemno. Port jest mechaniczny.

**Zakres produktu**
- **Diagnostyka jest pod `?debug=1`.** Symulator sieci i podgląd stanu CRDT były przez
  pewien czas widoczne w interfejsie; to była pomyłka co do adresata
  ([ADR-0007](docs/adr/0007-zakres-ui.md), sekcja o rewizji). Użytkownik ma widzieć produkt,
  nie stanowisko pomiarowe.
- **Brak formatowania tekstu.** Rich text w CRDT to inny algorytm (Peritext), nie dodatek —
  markery muszą przetrwać współbieżne wstawienia w środek zakresu. Świadomie poza zakresem
  ([ADR-0007](docs/adr/0007-zakres-ui.md)).
- **Brak komentarzy i uprawnień.** Jw.
- **Cofanie działa tylko w obrębie sesji.** `UndoManager` cofa własne operacje i scala
  ciągłe pisanie w jeden krok, ale stos żyje w pamięci karty i znika po przeładowaniu.

**Infrastruktura i eksploatacja**
- **Uwierzytelnianie bez hasła**, token podpisany HMAC, bez unieważniania i rotacji klucza.
  Token unieważniony w trakcie sesji nie zrywa połączenia, bo sprawdzamy go raz, przy `CONNECT`.
- **Log operacji rośnie bez ograniczeń.** Potrzebna archiwizacja starych segmentów do storage'u
  obiektowego i kompakcja.
- **Presence jest lokalne dla instancji** — klient podłączony do innej instancji nie pojawia
  się na liście obecnych. Wymaga osobnego kanału i wygasania wpisów.
- **Klient odtwarza cały log przy otwarciu dokumentu.** Serwer wewnętrznie używa snapshotów;
  klient powinien dostawać zserializowany snapshot CRDT, a nie komplet operacji.
- **Brak rate limitingu i shardingu dokumentów.** Jeden dokument z bardzo wieloma
  użytkownikami zawsze obciąża tyle instancji, ile trzyma go w pamięci.
- **Zapisy do jednego dokumentu są serializowane** blokadą wiersza, bo ciągłość `server_seq`
  jest wymogiem wykrywania luk. To pierwsze miejsce, które ograniczy przepustowość.
- **Brak SockJS fallback** — klient za proxy blokującym `Upgrade` nie połączy się wcale.
- **Okno GC (7 dni) to wartość zgadnięta**, nie wyliczona z telemetrii.

**Otwarte, nierozstrzygnięte**
- **Limit porcji resynchronizacji jest liczony w operacjach, a nie w bajtach**
  (`SYNC_CHUNK = 300`). Zakłada to, że operacja waży ~100 B — co przestaje być prawdą przy
  wklejeniu dużego fragmentu tekstu, bo to jest **jedna** operacja o dziesiątkach kilobajtów.
  Ścieżka rozgłoszeń została w CP10 przeniesiona na budżet bajtowy; ścieżka resynchronizacji
  nie. Znany dług, nie przeoczenie.
- **Liczby wydajnościowe nadal pochodzą z jednego procesu.** Klienci, serwer, Postgres
  i Redis dzielą dziesięć rdzeni, więc harness sam jest poważnym konsumentem procesora,
  a latencja sieci wynosi zero. Liczby opisują koszt systemu, nie doświadczenie użytkownika
  przez internet — do tego trzeba by je powiększyć o RTT łącza i rozdzielić stanowisko
  na osobne maszyny.

---

## Obserwowalność

```bash
docker compose --profile observability up -d   # Prometheus, Tempo, Grafana na :3001
```

Pojedyncze naciśnięcie klawisza tworzy **jeden trace obejmujący dwie usługi**: span
`edit.submit` powstaje w przeglądarce, a `op.receive`, `crdt.merge` i `op.broadcast.local`
po stronie serwera. Kontekst wędruje nagłówkiem ramki STOMP, bo uścisk dłoni WebSocketa
zdarza się raz na sesję i nagłówek HTTP opisywałby całą sesję zamiast jednej edycji.

Zapis do logu jest powiązany **linkami**, nie zagnieżdżeniem: obejmuje wiele operacji naraz
i dzieje się na innym wątku, więc zagnieżdżenie pokazywałoby pozostałym trace'om czas cudzego
zapisu.

[Dashboard](ops/grafana/dashboards/collabedit.json) jest plikiem w repo, nie stanem klikniętym
w interfejsie. Każdy panel ma opis mówiący, co **zła** wartość oznacza — łącznie z tym, że
stale zerowa liczba napraw luk w numeracji nie jest dobrą wiadomością, tylko sygnałem, że
mechanizm naprawczy nigdy się nie uruchomił.

---

## Nagranie demo

```bash
./tools/reset-demo-data.sh          # czyści dokumenty po testach
cd frontend && npm run demo         # nagrywa scenariusz (Playwright)
cd .. && ./tools/make-gif.sh        # wideo → docs/demo.gif
```

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
tools/         skrypty pomocnicze (nagranie demo, czyszczenie danych)
```

---

## Stan projektu

Wszystkie dziewięć checkpointów z [planu v1](docs/IMPLEMENTATION_PLAN.md) jest zamkniętych.
Dalszy kierunek — rich text, komentarze, wiele dokumentów i uprawnienia — opisuje
[plan v2](docs/PLAN-v2.md); jego pierwszy checkpoint (CP10, fundament) jest w trakcie.
README było pisane równolegle z kodem, sekcja po sekcji — nie odtwarzane z pamięci na końcu.

| Warstwa | Testy |
|---|---:|
| `crdt-core` — property, niezmienniki, konformancja, snapshoty | 20 |
| `app` — integracyjne na prawdziwym Postgresie i Redisie, w tym chaos klastra | 63 |
| `frontend` — zgodność z implementacją w Javie, most do CodeMirrora | 33 |
| end-to-end — dwie prawdziwe przeglądarki | 6 |
| **razem** | **122** |

Harness obciążeniowy, benchmarki JMH i pomiar fan-outu są wyłączone z normalnego przebiegu —
trwają minuty i mierzą przepustowość stanowiska tak samo jak systemu, więc nie są testami
poprawności. Uruchamiane jawnie, polecenia w sekcji [Testy](#testy).
