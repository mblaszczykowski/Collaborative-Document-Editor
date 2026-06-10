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

Ten sam rdzeń unosi **formatowanie tekstu i komentarze**. Nie są to funkcje doklejone obok
algorytmu, tylko jego zastosowania: znacznik formatowania to zakres między kotwicami
zaczepionymi w znakach, a kotwica komentarza to ten sam mechanizm z identyfikatorem rozmowy
zamiast rodzaju formatowania. Warstwa sekwencji znaków nie zmieniła się przy tym **ani
o jedno pole** ([ADR-0010](docs/adr/0010-rich-text.md)).

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
4. **[Rich text w CRDT to inny problem niż formatowanie w edytorze](docs/adr/0010-rich-text.md).**
   Wystarczy jedno zdanie, żeby zobaczyć dlaczego: Ania pogrubia zakres, Bartek — nie widząc
   tego — wpisuje tekst w jego środek. Przy formatowaniu zapisanym jako atrybut znaku powstaje
   dziura, której nikt nie zrobił. Rozwiązanie (kotwice zamiast offsetów) jest tą samą zasadą,
   którą projekt zastosował już przy kursorach — i to jest najlepszy argument, że tamta
   decyzja była trafna.

Siedem błędów w tym projekcie znalazły testy albo pomiar, a nie przegląd kodu: dublowanie
dokumentu przy otwarciu, snapshot niezapisywalny powyżej 64 KB, odpowiedź na resynchronizację
przekraczająca limit ramki, nazwa użytkownika ze znakiem `|` unieważniająca własny token,
GC kasujący kotwice formatowania, zaznaczenie od pozycji zero niewidoczne dla panelu
komentarzy i — najgroźniejszy — **operacje odrzucane przez limit tempa i gubione po cichu**.
Wszystkie są opisane w commitach i w komentarzach w miejscu naprawy.

---

## Architektura

```
┌─ frontend (React + TypeScript, Vite) ───────────────────────┐
│  CodeMirror 6 — WYŁĄCZNIE warstwa widoku (ADR-0006)          │
│  crdt/        — własna implementacja RGA + warstwa znaczników │
│  transport/   — STOMP + bufor offline + symulator sieci      │
│  ui/          — lista dokumentów, historia, komentarze       │
└──────────────────────────┬──────────────────────────────────┘
                           │  WebSocket / STOMP
                           │  (traceparent w nagłówku ramki)
┌──────────────────────────▼──────────────────────────────────┐
│ app — Spring Boot 3, Java 21                                 │
│   ws/        STOMP, presence, autoryzacja ramek, limit tempa  │
│   auth/      role, uprawnienia, token                         │
│   domain/    replika dokumentu w pamięci                      │
│   persist/   log operacji + snapshoty + katalog dokumentów    │
│   history/   time travel                                      │
│   comments/  wątki (treść poza CRDT — ADR-0011)               │
│   cluster/   Redis pub/sub + anti-entropy                     │
└────────┬─────────────────────────┬──────────────────────────┘
         │                         │
    PostgreSQL                  Redis
  (źródło prawdy)        (zawodna ścieżka szybka)

crdt-core/ — osobny moduł Mavena, ZERO zależności od Springa.
             Algorytm + warstwa formatowania + property testy + JMH.
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
| [0007](docs/adr/0007-zakres-ui.md) | Język wizualny mockupu przyjęty; jego zakres funkcjonalny odrzucony — **zastąpiony przez 0008** |
| [0008](docs/adr/0008-rozszerzenie-zakresu.md) | Rozszerzenie zakresu z artefaktu do produktu, z warunkiem utrzymania głębi rdzenia |
| [0009](docs/adr/0009-model-uprawnien.md) | Role uporządkowane, egzekwowane w obu warstwach z jednej reguły |
| [0010](docs/adr/0010-rich-text.md) | Formatowanie jako osobna warstwa CRDT (Peritext), nie atrybut znaku |
| [0011](docs/adr/0011-komentarze.md) | Kotwica komentarza w CRDT, treść rozmowy poza nim |

Diagramy (architektura, droga pojedynczej edycji, stany połączenia):
[`docs/architektura.md`](docs/architektura.md).
Szczegóły implementacyjne algorytmu: [`docs/crdt-design.md`](docs/crdt-design.md).
Plan pracy i checkpointy: [v1](docs/IMPLEMENTATION_PLAN.md) (CP1–CP9), [v2](docs/PLAN-v2.md) (CP10–CP15).

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
cd frontend && npm test                                      # testy jednostkowe klienta (vitest)
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
| `formattingConverges` | Znaczniki scalają się inaczej niż tekst — dokument zgodny, ale wygląda różnie u dwóch osób |
| `textInsertedIntoMarkedRangeInheritsMark` | Pisanie w środku pogrubienia tworzy dziurę, której nikt nie zrobił — powód, dla którego kotwice zastąpiły offsety ([ADR-0010](docs/adr/0010-rich-text.md)) |
| `markWaitsForItsAnchors` | Znacznik dotarł przed znakiem, w którym jest zaczepiony — cicho ginie zamiast poczekać |
| `garbageCollectionSparesMarkAnchors` | GC kasuje kotwicę: formatowanie znika **nie przy edycji, tylko przy najbliższym snapshocie**, więc winowajca jest już dawno poza podejrzeniem |
| `textTypedOverDeletedRangeDoesNotInheritItsMark` | Odwrotność powyższego — chroniona kotwica zaczyna formatować tekst, który powstał po skasowaniu zakresu |
| `websocketCannotSubscribeToForeignPrivateDocument` | Znajomość identyfikatora wystarcza do czytania cudzego dokumentu — dziura, którą **otworzył** CP11 i którą CP12 zamknął w obu warstwach |
| `viewerCannotWriteText` / `commenterCannotChangeText` | Uprawnienia egzekwowane tylko w interfejsie; ramka wysłana z konsoli przechodzi |
| `threadSurvivesDeletionOfAnchoredText` | Skasowanie tekstu kasuje rozmowę o nim — utrata kontekstu decyzji, po którą ludzie wracają po miesiącach |

**Czy te testy mają zęby — sprawdzone, nie założone.** Odwrócenie warunku skanu RGA
w kodzie produkcyjnym wywala 9 z 13 testów zbieżności. Usunięcie aktualizacji agregatów
w treapie wywala dokładnie te dwa, które to opisują. Wyłączenie ochrony kotwic w GC wywala
`garbageCollectionSparesMarkAnchors` — ale za **pierwszym** razem go nie wywalało, bo test
zakotwiczył się na znaku, który i tak był chroniony jako origin następnego bloku. Test
przeszedł przy wyłączonym mechanizmie, który miał sprawdzać. To był **trzeci raz**, kiedy
mutacja ujawniła test niesprawdzający niczego — [notatka o tym, czego to
nauczyło](docs/crdt-design.md#czego-to-nauczyło-o-testach).

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
| pusty | 2 | 27 ms | 29 ms | 30 ms | 55 ms |
| pusty | 10 | 74 ms | 83 ms | 86 ms | 516 ms |
| pusty | 25 | 114 ms | 154 ms | 165 ms | 513 ms |
| pusty | 50 | 202 ms | 360 ms | 374 ms | 499 ms |
| 10 000 znaków | 2 | 9,3 ms | 10 ms | 10 ms | 60 ms |
| 10 000 znaków | 10 | 28 ms | 31 ms | 32 ms | 60 ms |
| 10 000 znaków | 25 | 46 ms | 76 ms | 84 ms | 107 ms |
| 10 000 znaków | 50 | 135 ms | 218 ms | 230 ms | 267 ms |
| 100 000 znaków | 2 | 4,5 ms | 586 ms | 586 ms | 603 ms |
| 100 000 znaków | 10 | 13 ms | 518 ms | 830 ms | 831 ms |
| 100 000 znaków | 25 | 67 ms | 92 ms | 695 ms | 712 ms |
| 100 000 znaków | 50 | 124 ms | 212 ms | 1299 ms | 1647 ms |

**Wszystkie dwanaście scenariuszy zbiega się**, w tym 50 klientów w dokumencie
100 tys. znaków. Rozmiar dokumentu praktycznie nie wpływa na medianę latencji — po wymianie
indeksu na treap wąskim gardłem przestała być struktura danych.

**Ogony (p99) są tu wyraźnie grubsze niż w poprzednim pomiarze i jest to CENA
OGRANICZANIA TEMPA**, wprowadzonego w CP12. Harness zasiewa dokument 100 tys. znaków
dwustoma operacjami wysłanymi bez przerwy, co wyczerpuje zryw limitu; odrzucone operacje
są ponawiane po sekundzie, i ta sekunda jest tym, co widać w p99. Zachowanie jest poprawne
— nic nie ginie — ale liczba jest gorsza i zostaje w tabeli taka, jaka jest.

### Profil ruchu — czego poprzednie pomiary NIE obejmowały

Ta tabela jest pierwszą, w której harness generuje **komplet ruchu produkcyjnego**:

| Rodzaj | Do CP15 | Teraz |
|---|---|---|
| operacje tekstowe | tak | tak |
| **obecność (kursory)** | **nie** | co drugie naciśnięcie klawisza |
| **formatowanie** | **nie** | co dziesiąta edycja |

Kursor jest najczęstszym zdarzeniem w całym systemie, a harness go nie wysyłał. Poprzednie
liczby opisywały więc system lżejszy niż ten, którego używa przeglądarka — w sposób
niemożliwy do zauważenia, dopóki się tego nie sprawdzi.

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
| **50 klientów w jednym dokumencie** | Zbiega się powtarzalnie — wszystkie 12 scenariuszy harnessu, łącznie z 50 klientami w dokumencie 100 tys. znaków. Wcześniejsza obserwacja „powyżej ~10 klientów zbieżność przestaje być powtarzalna" okazała się **wadą stanowiska pomiarowego, nie systemu** — patrz [Punkt degradacji](#punkt-degradacji--rozstrzygnięty-i-to-nie-na-korzyść-mojego-stanowiska). | `BroadcastFanoutIT`, harness CP15 |
| **Przekroczenie limitu tempa** | Serwer odrzuca ramkę i **odsyła `RATE_LIMITED`**, a klient ponawia po sekundzie z bufora niepotwierdzonych. Odrzucenie z powodu uprawnień jest ciche (informacja „ten dokument istnieje" byłaby wyciekiem), z powodu tempa — nie: cicha odmowa tempa jest utratą danych. | harness CP15: 351 odrzuceń, zero utraconych operacji |
| **Skasowanie sformatowanego tekstu** | Formatowanie zostaje na tym, co przetrwało. Kotwice znaczników są tombstone'ami chronionymi przed GC — bez tego wygląd dokumentu znikałby przy sprzątaniu pamięci, i to nie w chwili kasowania, tylko przy najbliższym snapshocie. | `garbageCollectionSparesMarkAnchors` |
| **Skasowanie skomentowanego tekstu** | Rozmowa **zostaje**, oznaczona jako odłączona, z zacytowanym fragmentem z chwili założenia. Kasowanie rozmowy w reakcji na edycję tekstu jest zachowaniem, którego nikt się nie spodziewa. | `threadSurvivesDeletionOfAnchoredText`, e2e |
| **Odebranie dostępu w trakcie sesji** | Działa w granicy 5 s (czas życia wpisu w cache'u uprawnień). Odebranie przez właściciela unieważnia cache natychmiast na TEJ instancji; pozostałe czekają na wygaśnięcie. | `revokingAccessTakesEffect` |

---

## Co zrobiłbym inaczej w produkcji

Lista świadomych uproszczeń i znanych granic. Powstawała **w trakcie** pracy, a nie
z pamięci na końcu — dlatego zawiera też rzeczy, które wyszły dopiero przy implementacji
i okazały się gorsze, niż zakładałem.

**Kilka pozycji z tej listy zostało w międzyczasie zrobionych.** Zostawiam je poniżej,
przekreślone, wraz z tym, co się przy okazji potwierdziło albo nie — sekcja o granicach
własnego rozwiązania traci sens, jeśli pokazuje tylko granice, które wciąż stoją. Pełny
rozrachunek: [PLAN-v2](docs/PLAN-v2.md).

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
- ~~**Brak formatowania tekstu.**~~ **Zrobione (CP13).** Diagnoza z tej listy była trafna —
  rich text w CRDT to rzeczywiście osobny algorytm, nie atrybut znaku. Peritext wszedł jako
  **druga warstwa obok sekwencji znaków**, która nie zmieniła się przy tym ani o jedno pole
  ([ADR-0010](docs/adr/0010-rich-text.md)). Pozostała granica: rozstrzyganie sprzecznych
  wartości tego samego typu znacznika jest O(m²) po liczbie znaczników w dokumencie —
  wystarczające przy setkach, nie przy dziesiątkach tysięcy.
- ~~**Brak komentarzy i uprawnień.**~~ **Zrobione (CP12, CP14).** Kotwica komentarza okazała
  się tym samym mechanizmem co formatowanie i **nie dołożyła ani jednej linijki do warstwy
  CRDT** ([ADR-0011](docs/adr/0011-komentarze.md)). Uprawnienia egzekwowane w obu warstwach
  z jednej reguły ([ADR-0009](docs/adr/0009-model-uprawnien.md)). Pozostałe granice: treść
  komentarzy nie działa offline (bufor obejmuje operacje CRDT, nie REST), panel odświeża się
  przez odpytywanie zamiast przez zdarzenie, brak powiadomień i wzmianek, a odebranie
  uprawnień działa w granicy 5 s cache'u.
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
- ~~**Brak rate limitingu**~~ **— jest (CP12), i nauczył mnie czegoś, czego nie zakładałem.**
  Pierwsza wersja odrzucała ramkę po cichu, bo tak działa odmowa z powodu uprawnień.
  Przy 100 tys. znaków i 50 klientach dało to 115 odrzuceń i dokument, który **przestał
  się zbiegać** — limit tempa nie jest odmową dostępu, tylko prośbą o zwolnienie, więc cicha
  odmowa jest po prostu utratą danych. Teraz serwer odsyła `RATE_LIMITED`, a klient ponawia
  z bufora niepotwierdzonych. Znalazł to pomiar, nie przegląd kodu.
- **Brak shardingu dokumentów.** Jeden dokument z bardzo wieloma użytkownikami zawsze
  obciąża tyle instancji, ile trzyma go w pamięci.
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
- **Rozstrzyganie znaczników jest kwadratowe po ich liczbie.** `MarkSet` przy każdym
  wyliczeniu formatowania przechodzi wszystkie znaczniki i dla każdego odcinka szuka
  zwycięzcy liniowo. Przy dokumencie z tysiącami komentarzy zacznie to być widoczne;
  właściwym rozwiązaniem jest drzewo przedziałowe indeksowane pozycją. Nie zmierzone,
  bo nie miałem realistycznego dokumentu tej wielkości — i wolę to napisać, niż zgadywać próg.
- **Autoryzacja zagląda w treść ramki.** Rola `commenter` wymaga odróżnienia znacznika
  komentarza od formatowania, a to znaczy parsowanie ładunku w interceptorze. Robimy to
  **tylko** dla tej jednej roli, ale jest to warstwa autoryzacji zależna od formatu danych —
  zmiana formatu operacji może cicho zmienić uprawnienia. W produkcji: typ operacji
  w nagłówku STOMP, nie w ciele.
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

Zamknięte są wszystkie checkpointy obu planów: dziewięć z [planu v1](docs/IMPLEMENTATION_PLAN.md)
(rdzeń CRDT, transport, historia, klaster, obserwowalność) i sześć z
[planu v2](docs/PLAN-v2.md) (wydajność rozgłoszeń, wiele dokumentów, uprawnienia,
formatowanie, komentarze, ponowny pomiar).

README było pisane równolegle z kodem, sekcja po sekcji — nie odtwarzane z pamięci na końcu.
Ta sama zasada dotyczy sekcji [o granicach](#co-zrobiłbym-inaczej-w-produkcji): pozycje,
które plan v2 zamknął, są w niej **przekreślone, a nie usunięte** — razem z tym, co się przy
okazji potwierdziło, i z tym, co okazało się nieprawdą.

| Warstwa | Testy |
|---|---:|
| `crdt-core` — property, niezmienniki, konformancja, snapshoty, znaczniki | 39 |
| `app` — integracyjne na prawdziwym Postgresie i Redisie, w tym chaos klastra i uprawnienia | 81 |
| `frontend` — zgodność z implementacją w Javie, most do CodeMirrora | 49 |
| end-to-end — dwie prawdziwe przeglądarki | 16 |
| **razem** | **185** |

Harness obciążeniowy, benchmarki JMH i pomiar fan-outu są wyłączone z normalnego przebiegu —
trwają minuty i mierzą przepustowość stanowiska tak samo jak systemu, więc nie są testami
poprawności. Uruchamiane jawnie, polecenia w sekcji [Testy](#testy).
