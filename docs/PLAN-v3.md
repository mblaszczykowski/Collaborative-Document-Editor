# Plan v3 — z czujności w konstrukcję

> **Status: CP16–CP20 zamknięte, §0 w większości nie. Audyt końcowy w [§10](#10-audyt-końcowy--po-cp20).** Następca [`PLAN-v2.md`](PLAN-v2.md) (CP10–CP15,
> zamknięty) i [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md) (CP1–CP9, zamknięty).
>
> | Checkpoint | Stan |
> |---|---|
> | §0 — Dotarcie (hosting, język, kondensacja README) | **w ~15 %** — patrz [audyt](#101-niezrobione--i-to-jest-najważniejsza-część-tego-audytu) |
> | CP16 — Autoryzacja wymuszona strukturalnie | **zamknięty** — przy okazji znaleziona czwarta dziura tej samej klasy |
> | CP17 — Deterministyczna symulacja | **zamknięty** — 806 scenariuszy < 1 s; zero błędów w systemie, trzy w samej symulacji |
> | CP18 — Blok bez treści, format snapshotu v3 | **zamknięty** — odzysk GC z 0 % na 100 % |
> | CP19 — Bramka regresji wydajności + automatyzacja bezpieczeństwa | **zamknięty inaczej** — bramka na kształt złożoności, bo progu na milisekundy nie dało się obronić |
> | CP20 — Zamiatanie w `MarkSet` + zaległe długi | **zamknięty częściowo** — `MarkSet` i `retry-after` zrobione, cztery długi nie |
>
> Zasady prowadzenia dokumentu przejęte z v1 i v2 bez zmian: jeśli decyzja z planu okaże się
> zła w praktyce, poprawiamy **tutaj** i commitujemy zmianę planu razem z powodem. Sekcja
> „audyt końcowy" (§10) jest pusta z założenia — wypełnia ją przejście punkt po punkcie
> **przeciw kodowi**, po zamknięciu CP20. W v2 to przejście znalazło otwarty endpoint historii,
> więc nie jest formalnością.

---

## Teza — czym v3 różni się od v2

v1 była **wąskim artefaktem**: jeden dokument, jeden problem, maksymalna głębia.
v2 była **zmianą tezy**: rich text, komentarze, uprawnienia, wiele dokumentów — dowód,
że rdzeń unosi produkt.

v3 **nie zmienia tezy i nie dokłada ani jednej funkcji produktowej.** Ma jeden motyw
przewodni, i wszystkie sześć pozycji spełniają go bez wyjątku:

> **Wszędzie tam, gdzie system dziś polega na tym, że ktoś pamięta, zauważy albo trafi —
> zastąpić to konstrukcją, która nie pozwala inaczej.**

| Dziś polegamy na tym, że… | v3 zastępuje to… |
|---|---|
| ktoś sklonuje repo i uruchomi `docker compose` | działającym linkiem (§0) |
| autor nowego kontrolera pamięta o sprawdzeniu uprawnień | testem architektury wywalającym build (CP16) |
| test chaosu **trafi** w złe uszeregowanie zdarzeń | ziarnem, które je odtwarza (CP17) |
| GC coś odzyska, choć pomiar mówi, że nie odzyskuje nic | reprezentacją, w której blok oddaje pamięć bez zbierania (CP18) |
| ktoś zauważy, że liczba w README się pogorszyła | bramką CI porównującą z baseline'em (CP19) |
| dokumenty pozostaną małe, bo przy dużych `MarkSet` zwalnia | złożonością `O(m log m)` zamiast `O(m²)` (CP20) |

To jest ta sama zasada, którą projekt zastosował już raz i opisał w README: *„zabezpieczenie
oparte na tym, że ktoś pamięta o punkcie z dokumentu, nie jest zabezpieczeniem"*. v3 rozciąga
ją z autoryzacji na cały system.

**Czego v3 świadomie nie robi: nie dokłada zakresu.** [ADR-0008](adr/0008-rozszerzenie-zakresu.md)
postawił warunek, że każda nowa funkcja ma wnosić własny problem inżynierski. Po CP15 nie
zostały już funkcje spełniające ten warunek — zostały **domknięcia**, i to jest cały ten plan.

---

## 0. Dotarcie — zanim ruszy CP16

Ta sekcja nie jest inżynierią i celowo stoi przed checkpointami. Powód jest niewygodny,
ale prawdziwy: **wszystko, co zbudowały v1 i v2, jest dziś dostępne wyłącznie dla kogoś,
kto sklonuje repozytorium i poczeka kilka minut na Mavena.** Recenzent z trzema minutami
tego nie zrobi. Najlepiej udowodniona teza świata nie działa, jeśli nikt nie dochodzi
do punktu, w którym ją widać.

### 0.1 Hostowana instancja

PLAN-v2 §11 wymienia to jako niezrobione (*„Docker Compose uruchamia całość jedną komendą;
hostingu nie ma"*). Do zrobienia: jedna instancja na Fly.io albo Railway, link **nad
pierwszym GIF-em** w README.

**Trzy rzeczy do rozstrzygnięcia przy okazji, bo inaczej hosting zaszkodzi bardziej,
niż pomoże:**

1. **Zimny start.** Darmowe plany usypiają kontenery. Pierwsze żądanie po uśpieniu to
   10–30 s białego ekranu — czyli dokładnie ten czas, po którym recenzent zamyka kartę,
   i **gorsze wrażenie niż brak linku**. Albo najtańszy plan bez usypiania, albo pinger,
   albo świadoma rezygnacja. GIF-y zostają w README niezależnie od decyzji, jako plan B.
2. **Dane demonstracyjne.** Instancja publiczna z jednym dokumentem, w którym każdy pisze
   po każdym, po tygodniu jest nieczytelna. Potrzebny reset (`tools/reset-demo-data.sh`
   już istnieje) uruchamiany cyklicznie.
3. **Sekret tokenu.** `TokenService` ma domyślny sekret `rozwojowy-sekret-nie-do-produkcji`.
   Na instancji publicznej musi przyjechać ze zmiennej środowiskowej — inaczej **każdy,
   kto przeczyta repo, wystawia sobie token dowolnego użytkownika**. To jest jedyny punkt
   tej sekcji, który jest realną dziurą, a nie kwestią odbioru.

### 0.2 Decyzja językowa

README, wszystkie ADR-y, komentarze w kodzie i komunikaty commitów są po polsku. To nie jest
wada — to jest **rozwidlenie, które trzeba wybrać świadomie, a nie przez inercję**:

| Wybór | Konsekwencja |
|---|---|
| Zostaje po polsku | Rynek PL. Komentarze tej jakości po polsku są wyróżnikiem, nie obciążeniem |
| Wszystko po angielsku | Rynek zdalny. Koszt: przepisanie ~4 000 linii dokumentacji, ryzyko utraty precyzji |
| **README + indeks ADR po angielsku, reszta po polsku** | Sygnalizuje dwujęzyczność zamiast ukrywać głębię. Koszt ~1 dzień |

Rekomendacja: trzecia opcja, jeśli celem jest rynek zdalny; pierwsza, jeśli PL. **Nie ma
tu dobrej odpowiedzi bez wiedzy, której ten dokument nie ma** — decyzja należy do autora
i powinna zostać wpisana tutaj, razem z powodem.

#### DECYZJA (podjęta przez autora): **angielski**, opcja druga

Cel to rynek zdalny, więc język przestaje być wyróżnikiem, a zaczyna być filtrem
odcinającym czytelników. Wybrana została opcja **pełna, nie kompromisowa** — bo repozytorium
w dwóch językach naraz czyta się gorzej niż w każdym z nich osobno: czytelnik trafiający
z angielskiego README na polski komentarz w kodzie dostaje sygnał „tu się kończy to,
co dla ciebie napisano".

**Kolejność migracji — od najczęściej czytanego do najrzadziej**, bo migracja tej wielkości
nie dzieje się jednym commitem i w trakcie repozytorium **będzie** niespójne:

| # | Co | Dlaczego w tej kolejności |
|---|---|---|
| 1 | `README.md` | Drzwi wejściowe. Czyta je 100 % odwiedzających, resztę — ułamek |
| 2 | `docs/adr/` | Druga rzecz, do której prowadzi README, i główny dowód na świadomość decyzji |
| 3 | Komentarze w kodzie i javadoc | Największa objętość, ale czytelnik dochodzi tu dopiero po 1 i 2 |
| 4 | `docs/architektura.md`, `docs/crdt-design.md` | Jw. |
| 5 | Plany (`IMPLEMENTATION_PLAN.md`, `PLAN-v2.md`, ten plik) | **Ostatnie i być może wcale.** To jest seria historyczna — zapis tego, co myślano w danym momencie. Tłumaczenie zapisu historycznego niesie ryzyko, że przy okazji „poprawi się" wnioski, które wtedy brzmiały inaczej |

**Nowa treść od tego commita powstaje po angielsku** — kod, testy, ADR-y i komunikaty
commitów. Ten dokument zostaje po polsku zgodnie z wierszem 5, jako część serii z v1 i v2.

**Konsekwencja negatywna, wpisana świadomie:** przez czas trwania migracji repozytorium jest
dwujęzyczne, czyli dokładnie w stanie, który powyżej nazwano najgorszym. Jest to koszt
przejścia, nie stan docelowy — ale gdyby migracja utknęła w połowie, byłby to najgorszy
możliwy wynik tej decyzji. Punkt kontrolny: jeśli po CP20 wiersze 1–3 nie są zrobione,
**lepiej wrócić do polskiego w całości** niż zostawić repozytorium w połowie drogi.

**Dlaczego to nie dostaje ADR-a:** ADR-y w tym projekcie opisują decyzje architektoniczne.
Język dokumentacji i wybór hostingu to decyzje redakcyjne i operacyjne. Pisanie ADR-a
dla wszystkiego jest tym samym błędem co stosowanie CRDT tam, gdzie nie ma współbieżnej
edycji ([ADR-0011](adr/0011-komentarze.md)) — kosztem bez korzyści.

### 0.3 Kondensacja README

README ma **630 linii**, a sekcja „co zrobiłbym inaczej w produkcji" — około trzydziestu
punktów. Sekcja jest jednym z najmocniejszych elementów tego repozytorium i **nie wolno jej
usunąć ani skrócić co do treści.** Ale istnieje próg, powyżej którego uczciwość co do granic
zaczyna się czytać jako lista rzeczy, które nie działają, i trzydzieści punktów jest już
za tym progiem.

Do zrobienia bez utraty ani jednego zdania: 4–5 najciekawszych pozycji zostaje na wierzchu,
reszta trafia do `<details>`. Zmiana czysto redakcyjna, odbiór inny.

**Po CP16–CP20 ta sekcja i tak wymaga przejścia**, bo pięć jej pozycji zostanie zamkniętych.
Konwencja domu obowiązuje: **przekreślić, nie usuwać**, razem z tym, co się przy okazji
potwierdziło i co okazało się nieprawdą.

---

## 1. Kolejność i dlaczego akurat taka

Tylko jedna zależność jest twarda, reszta to kolejność wg zwrotu:

```
§0    Dotarcie ─────────────────┐  Nie blokuje niczego technicznie, ale każdy dzień
      (hosting, język, README)  │  bez linku to dzień, w którym gotowa praca jest
                                │  niewidoczna. Dlatego pierwsze, nie ostatnie.
                                ▼
CP16  Autoryzacja ──────────────┐  ZALEŻNOŚĆ TWARDA, i to podwójna:
      strukturalnie             │  (a) hosting publiczny bez tego wystawia
                                │      model uprawnień, który trzyma się
                                │      czujności autora — a ta zawiodła
                                │      już dwa razy (CP11, HistoryController);
                                │  (b) CP18 rusza reprezentację bloku, więc
                                │      chcemy mieć testy dostępu zabetonowane
                                │      ZANIM ruszy się rdzeń.
                                ▼
CP17  Deterministyczna ─────────┐  Przed CP18, nie po. DST ma być narzędziem
      symulacja                 │  DO przeprojektowania bloku, a nie sposobem
                                │  sprawdzenia po fakcie, czy się udało.
                                │  Odwrotna kolejność marnuje jego jedyną
                                │  przewagę: odtwarzalność awarii.
                                ▼
CP18  Blok bez treści ──────────┐  Jedyna zmiana w rdzeniu w całym planie.
      (snapshot v3)             │  Po niej wszystkie liczby wydajnościowe
                                │  opisują inny system — dokładnie tak jak
                                │  po CP13.
                                ▼
CP19  Bramka regresji ──────────┐  Musi być PO CP18, bo baseline zdjęty przed
      + bezpieczeństwo          │  zmianą rdzenia byłby baseline'em systemu,
                                │  którego już nie ma.
                                ▼
CP20  MarkSet + długi              Niezależny od wszystkiego powyżej.
                                   Celowo ostatni: to jest jedyny checkpoint,
                                   który da się bezboleśnie uciąć, jeśli
                                   budżet się skończy.
```

**Sensowne punkty zatrzymania**, gdyby budżet nie starczył na całość: koniec CP16 (projekt
bezpieczny do wystawienia publicznie) albo koniec CP17 (nowy filar testowy, spójna całość).
**Zatrzymanie w środku CP18 nie jest punktem zatrzymania** — reprezentacja bloku byłaby
rozjechana między Javą a TypeScriptem, a korpus konformancyjny przestałby cokolwiek chronić.

---

## 2. CP16 — Autoryzacja wymuszona strukturalnie (2–2,5 dnia)

### 2.1 Problem

Autoryzacja jest dziś sprawdzana w **około piętnastu miejscach w czterech plikach**:
`DocumentRestController`, `HistoryController`, `CommentController`, `DocumentAccessInterceptor`.
Każde sprawdzenie z osobna jest poprawne. Nic nie wymusza istnienia następnego.

Ta klasa błędu **ugryzła ten projekt dwa razy**:

1. CP11 otworzył IDOR-a (znajomość identyfikatora = dostęp do dokumentu), CP12 go zamknął
   w dwóch warstwach;
2. `HistoryController` **nie sprawdzał uprawnień w ogóle** przez cały checkpoint — trzy
   endpointy, w tym pełny odczyt treści cudzego dokumentu i jego nadpisanie. Znalazł to
   dopiero audyt końcowy planu, mimo że plan wymieniał tę ścieżkę wprost.

README nazywa właściwe rozwiązanie i nazywa je dobrze:

> *„jedna reguła na wszystkie ścieżki `/api/documents/{id}/**`, wymuszona filtrem albo
> `@PreAuthorize` — czyli miejsce, w którym pominięcie sprawdzenia wymaga świadomej decyzji,
> a nie przeoczenia"*

### 2.2 Decyzja — i dlaczego o krok dalej, niż mówi README

Sama centralizacja **nie jest wystarczająca**, bo nie broni przed endpointem, który po prostu
nie pasuje do wzorca ścieżki. Dokładnie tak zniknął `HistoryController`: był zamontowany pod
`/api/documents/{documentId}/history`, czyli w miejscu, w którym reguła by go objęła — gdyby
reguła istniała. Ale następny może wylądować pod `/api/threads/...` i znów nikt tego
nie zauważy.

Dlatego CP16 to **dwie rzeczy, nie jedna**:

**(a) Jedna reguła.** Filtr albo `@PreAuthorize` na `/api/documents/{id}/**`, wyliczający
rolę raz i wstawiający ją do kontekstu żądania. Kontrolery przestają wołać `PermissionService`
i zaczynają czytać gotową rolę.

**(b) Test architektury, który wywala build.** ArchUnit (nowa zależność, tylko testowa):

- każda metoda z adnotacją `@GetMapping`/`@PostMapping`/… w pakiecie `app` **albo** jest
  pokryta regułą ścieżkową, **albo** ma jawną adnotację `@PublicEndpoint` z uzasadnieniem;
- to samo dla `@MessageMapping`;
- lista endpointów publicznych jest **wypisana w teście**, więc dodanie nowego wymaga
  dopisania go tam — czyli świadomej decyzji, dokładnie tak jak chce README.

Trzy endpointy są dziś legalnie publiczne i trafiają na tę listę: `POST /api/login`,
`GET /api/documents` (filtruje po uprawnieniach w środku, bo lista musi zwrócić „to,
co widzisz", a nie 403), `POST /api/documents`.

**ADR-0012 — autoryzacja wymuszona strukturalnie.** Do napisania przed kodem, układ jak
w pozostałych. Konsekwencje negatywne do opisania szczerze, bo są realne:

- reguła ścieżkowa jest **mniej czytelna w punkcie użycia** niż jawne `if` w kontrolerze —
  czytelnik kontrolera nie widzi już, co go chroni;
- `commenter` wymaga zaglądania w treść ramki (README, „autoryzacja zagląda w treść ramki"),
  a tego reguła ścieżkowa nie rozwiązuje — zostaje w interceptorze i zostaje długiem;
- ArchUnit to zależność, która **potrafi wywalić build z powodu zmiany nazwy pakietu**,
  czyli fałszywy alarm w zamian za prawdziwe.

### 2.3 Znalezione przy pisaniu tego planu — `POST /api/documents/recovered`

**Sprawdzone przeciw kodowi, nie podejrzewane.** Ten endpoint ustawia właściciela
i widoczność `private`, ale **wyłącznie wewnątrz `if (caller != null)`**. A `caller`
bywa `null` częściej, niż wygląda:

1. `TokenAuthFilter` **z założenia nie odrzuca** żądań bez tokenu — *„ustala jedynie
   tożsamość; decyzję o odmowie podejmuje kontroler"*. To jest świadoma i dobrze
   udokumentowana decyzja.
2. Ten konkretny kontroler **decyzji o odmowie nie podejmuje** — nie ma tu żadnego
   sprawdzenia.
3. Więc przy nieobecnym **albo wygasłym** tokenie dokument powstaje bez właściciela,
   a `visibility` przyjmuje wartość domyślną ze schematu: **`'link'`** (`V3__permissions.sql:21`).
4. A reguła 4 w `PermissionService` mówi: widoczność `link` → **`EDITOR` dla każdego
   zalogowanego**.

Czyli szkic, który commit `b5c6ba1` uczynił prywatnym („odzyskany szkic nie jest publiczny"),
**wraca do bycia edytowalnym przez wszystkich, jeśli tylko żądanie przyjdzie bez ważnego
tokenu**. Naprawa zamknęła ścieżkę uwierzytelnioną i ominęła nieuwierzytelnioną.

**Dlaczego to nie jest scenariusz teoretyczny — i to jest tu najciekawsze.** Token żyje
12 godzin (`TokenService.LIFETIME`). Ten endpoint istnieje **wyłącznie po to, żeby obsłużyć
klienta, który był offline dłużej niż okno GC** (README, failure mode `RESYNC_REQUIRED`,
okno 7 dni). Populacja, dla której endpoint powstał, to więc dokładnie ta populacja,
której token na pewno wygasł. Ścieżka nieuwierzytelniona nie jest tu przypadkiem brzegowym —
jest **przypadkiem typowym**.

Skutkiem nie jest wyciek cudzych danych (treść podaje sam wywołujący), tylko **odwrotność
intencji `b5c6ba1`**: prywatna praca ląduje jako dokument otwarty do edycji dla wszystkich,
w momencie, w którym użytkownik martwi się o jej utratę. Dodatkowo endpoint pozwala tworzyć
dokumenty **bez jakiegokolwiek uwierzytelnienia**.

**To jest trzecia odsłona tej samej klasy błędu** (po IDOR-ze z CP11 i `HistoryController`),
i najlepszy możliwy argument za tym, dlaczego CP16 jest w tym planie pierwszy: znalazł ją
przegląd, a nie test — czyli znowu czujność, a nie konstrukcja. Reguła z §2.2 obejmuje
`/api/documents/**`, więc zamyka ją **bez pisania osobnej poprawki**; test z listą endpointów
publicznych pilnuje, żeby nikt jej nie otworzył ponownie.

### 2.4 Testy

| Test | Jaki realny failure mode wyłapuje |
|---|---|
| `everyDocumentScopedEndpointIsCoveredByTheRule` (ArchUnit) | **Nowy kontroler startuje bez autoryzacji i milczy o tym** — dokładnie `HistoryController` z CP14 |
| `everyMessageMappingIsCoveredByTheRule` (ArchUnit) | Jw. dla ścieżki WebSocket, którą zamknął CP12 |
| `publicEndpointsAreExactlyTheseThree` | Ktoś dopisuje endpoint do listy publicznych, żeby uciszyć test — lista jest jawna, więc widać to w diffie |
| `recoveredDraftIsPrivateEvenWithoutAToken` | **Znalezione w §2.3.** Wygasły token sprawia, że odzyskany szkic powstaje jako `link`, czyli edytowalny dla wszystkich — odwrotność intencji `b5c6ba1`. Test musi jechać **bez nagłówka `Authorization`**, bo tylko ta ścieżka jest dziurawa |
| `roleIsResolvedOncePerRequest` | Regresja wydajności: reguła + kontroler pytają `PermissionService` dwa razy na żądanie |
| cały istniejący `PermissionIT` | **Regresja dostępu wprowadzona przez sam refaktor** — to jest główne ryzyko tego checkpointu, patrz §8 |

**Weryfikacja mutacją, zgodnie z konwencją domu:** usunięcie reguły ma wywalić `PermissionIT`,
a nie tylko test architektury. Jeśli wywala wyłącznie ArchUnit, znaczy że reguła nie jest
podpięta do niczego, co ma znaczenie.

---

## 3. CP17 — Deterministyczna symulacja (4–6 dni)

### 3.1 Problem — najlepiej widać go w `nightly.yml`

Nocny przebieg uruchamia `ClusterChaosIT` **dziesięć razy pod rząd** i szuka niedeterminizmu.
Komentarz w workflow jest szczery: *„test, który przechodzi raz, mógł przejść przypadkiem;
test, który przechodzi dziesięć razy pod rząd, jest przesłanką znacznie mocniejszą"*.

Przesłanką — nie dowodem. I co ważniejsze: **awaria, gdy w końcu wystąpi, nie jest
odtwarzalna.** Wynik zależy od uszeregowania wątków i od obciążenia runnera. Debugowanie
takiego przebiegu polega na uruchamianiu go w pętli i nadziei.

To jest jedyne miejsce w tym projekcie, gdzie testowanie opiera się na trafieniu.
Property testy w `crdt-core` już tego problemu nie mają — mają stałe ziarno i determinizm,
i to jest dokładnie ta własność, której brakuje warstwie rozproszonej.

### 3.2 Decyzja — trzeci filar testowy

Podejście z FoundationDB i TigerBeetle: **jednowątkowy, deterministyczny scheduler, w którym
ziarno steruje wszystkim, co dziś jest przypadkiem** — kolejnością dostarczenia wiadomości,
opóźnieniami, partycjami sieci, momentem pada i powrotu instancji, momentem zapisu do bazy.

Konsekwencja, dla której warto to zrobić: **nieudany przebieg przestaje być „raz na dziesięć"
i staje się `@Test void seed_8471293()`** — jednolinijkowym testem regresyjnym, który
odtwarza dokładnie to samo uszeregowanie zdarzeń na każdej maszynie.

**Połowa klocków już leży**, i to jest główny argument za tym, że mieści się w 4–6 dniach:

| Jest | Do napisania |
|---|---|
| `ReplicaSet`, `EditScript` — sterowane repliki i skrypty edycji | Scheduler zdarzeń z kolejką priorytetową po czasie wirtualnym |
| `simulator.ts` — symulator opóźnień i utraty pakietów po stronie klienta | Jego odpowiednik po stronie serwera, sterowany ziarnem |
| `ClusterChaosIT` — scenariusz zatrzymania Redisa | Ten sam scenariusz jako **zdarzenie w harmonogramie**, nie jako `Thread.sleep` |
| `RgaInvariants` — niezmienniki do sprawdzania | Sprawdzanie ich **po każdym zdarzeniu**, nie tylko na końcu |

### 3.3 Zakres — zamknąć wąsko, potem rozszerzać

To jest to samo ryzyko, które w v2 dotyczyło CP13, i to samo przeciwdziałanie: **zamknąć
na jednym scenariuszu z pełnym determinizmem, dopiero potem rozszerzać.** Scenariusze
są tanie, scheduler jest drogi.

Pierwszy scenariusz: **partycja Redisa w trakcie pisania przez N replik** — czyli dokładnie
to, co dziś robi `ClusterChaosIT`, tylko odtwarzalnie. Dopiero po nim: pad instancji,
opóźniony zapis, klient wracający po oknie GC.

**Twardy warunek zaliczenia checkpointu, bez którego to jest tylko ładniejszy test chaosu:**
to samo ziarno musi dawać **bajt w bajt ten sam log zdarzeń** na dwóch różnych maszynach
i przy dwóch różnych obciążeniach. Test pilnujący tego jest ważniejszy od wszystkich
scenariuszy razem wziętych — bo bez niego cała przewaga znika, a nikt tego nie zauważy.

**Czego to nie zastąpi:** symulacja nie dotyka prawdziwego Postgresa, prawdziwego Redisa ani
prawdziwej sieci. `ClusterChaosIT` na Testcontainers **zostaje** — symulacja szuka błędów
logiki rozproszonej, integracyjne pilnują, że model zgadza się z rzeczywistością. Zastąpienie
jednego drugim byłoby tą samą pomyłką co uznanie snapshotu za źródło prawdy.

**ADR-0013 — deterministyczna symulacja jako trzeci filar testów.** Konsekwencje negatywne
do opisania: kod produkcyjny musi przyjąć zegar i źródło losowości z zewnątrz (`Clock`,
`RandomGenerator`) — czyli **testowalność wycieka do sygnatur**; symulator to druga
implementacja warstwy transportowej, więc **ten sam dług, co przy dwóch implementacjach CRDT**,
tylko mniejszy; ziarno chroni przed niedeterminizmem symulacji, nie przed niedeterminizmem
JVM (GC, JIT) — i to jest granica, której ten checkpoint nie przekracza.

### 3.4 Testy

| Test | Jaki realny failure mode wyłapuje |
|---|---|
| `sameSeedProducesIdenticalEventLog` | **Symulacja nie jest deterministyczna** — czyli cały checkpoint nie działa, a wygląda, że działa |
| `redisPartitionUnderSeed(...)` ×1000 ziaren | Uszeregowanie zdarzeń, którego `ClusterChaosIT` nie trafił w dziesięciu przebiegach |
| `invariantsHoldAfterEveryEvent` | Rozspójnienie **przejściowe** — struktura zła między dwoma zdarzeniami, poprawna na końcu, więc sprawdzenie końcowe go nie widzi |
| `knownFailingSeedStaysFixed` | Regresja naprawionego błędu — to jest ta pozycja, dla której robi się cały ten checkpoint |

---

## 4. CP18 — Blok bez treści, format snapshotu v3 (3–5 dni)

### 4.1 Dlaczego to przestało być opcjonalne

[ADR-0005](adr/0005-tombstone-gc.md) po pomiarze z CP15 mówi wprost: *„kierunek się nie zmienia,
ale **przestaje być opcjonalny**"*. Zmierzone: **0 % odzysku GC** przy tekście napisanym ciągiem,
80 % przy tekście pisanym znak po znaku. Ta sama implementacja, ten sam tekst, te same
znaczniki — różni się wyłącznie historia pisania.

Przyczyna jest w `RgaDocument.gc` (linia 492) i w `isAnyCharReferenced` (linia 540): **GC
iteruje po blokach, a warunek referencji jest warunkiem na blok, nie na znak.** Jedna kotwica
ratuje cały blok run-length.

Jedna zmiana zamyka **dwa** udokumentowane ograniczenia naraz:

1. „GC odzyskuje mniej, niż zakłada ADR-0005" — tombstone w środku tekstu jest originem
   następnego bloku, więc nie wolno go usunąć;
2. „Kotwica formatowania blokuje CAŁY blok run-length" — zmierzone w CP15.

Obydwa mają tę samą przyczynę i tę samą odpowiedź, wskazaną już w ADR-0005:

> **Wszystkie trzy warunki ochrony potrzebują TOŻSAMOŚCI znaku, a nie jego treści.**

### 4.2 Zmiana

Dziś `Block` (`crdt-core/.../Block.java`) trzyma `Id start`, `String text`, `Id originLeft`,
`boolean deleted`, z niezmiennikiem **„blok nie może być pusty"** i `length()` liczonym
jako `text.length()`.

Po zmianie blok usunięty **porzuca treść, zachowując tożsamość i długość**. Niezmiennik
zmienia brzmienie z „blok nie może być pusty" na **„blok ma dodatnią długość, ale blok
usunięty może nie mieć treści"**. `length()` staje się polem, nie funkcją tekstu.

Dzięki temu blok **oddaje pamięć nie będąc zbieranym** — a wtedy cała trudność z §4.1 znika,
bo nie trzeba go usuwać ze struktury, żeby odzyskać miejsce.

**Co to rusza — pełna lista, bo koszt jest tu ciekawszy niż zmiana:**

| Miejsce | Dlaczego |
|---|---|
| `Block`, `RgaDocument.gc` | rdzeń zmiany |
| `TreapBlockList` | agregaty liczą się z `length()`, które przestaje pochodzić z tekstu |
| `ArrayBlockList` | jw.; obie implementacje muszą zgadzać się dalej (`BlockListEquivalenceProperties`) |
| `SnapshotCodec`, `DocumentSnapshot` | `CURRENT_FORMAT_VERSION` 2 → 3 |
| `frontend/src/crdt/block.ts`, `document.ts` | **bliźniak w TypeScripcie** — dług z README materializuje się tutaj po raz drugi |
| `conformance/` | korpus do regeneracji; job CI pilnuje, że się nie rozjedzie |

**Migracja danych jest darmowa i to nie przypadek.** `SnapshotCodec` przy niezgodnej wersji
**ignoruje snapshot zamiast go migrować** ([ADR-0003](adr/0003-historia-zmian.md): snapshot
to usuwalny cache), więc stan odtworzy się z logu operacji. Prawdziwy koszt tego checkpointu
to bliźniak w TS i agregaty treapa, a nie dane — i warto to napisać w ADR-ze, bo decyzja
z CP5 („snapshot to cache, nie źródło prawdy") **właśnie się tu spłaca po raz drugi**.

### 4.3 Rozliczenie z przepuszczoną okazją

ADR-0005 przyznaje, że najtańszy moment na tę zmianę — podniesienie formatu do wersji 2
w CP13 — **został przepuszczony**, bo brakowało pomiaru. Pomiar jest, wypadł źle, więc
okazja wraca przy wersji 3.

Do dopisania w ADR-0005 po zamknięciu CP18, zgodnie z konwencją domu: **czy odłożenie
decyzji faktycznie kosztowało tyle, ile zakładano.** Jeśli okaże się, że zrobienie tego
w CP13 byłoby tańsze — to jest wniosek wart zapisania. Jeśli okaże się, że bez pomiaru
zmiana poszłaby w złą stronę, to jest wniosek jeszcze ciekawszy.

### 4.4 Pomiar — obowiązkowy, tak jak w CP15

Ten checkpoint **bez powtórzenia `MarkCostMeasurement` jest niezamknięty.** Ta sama tabela
(1000 znaków, tekst skasowany, 100 znaczników, stabilny wektor wersji), te same dwie
skrajności, kolumna „po CP18" obok istniejących.

**Do zweryfikowania:** czy odzysk przy tekście pisanym ciągiem rośnie z 0 % do wartości
zbliżonej do 100 %. Jeśli nie — hipoteza z ADR-0005 jest błędna i trzeba to napisać wprost,
zamiast dopasowywać interpretację do wyniku. To już raz się w tym projekcie zdarzyło
(warunek skanu RGA uznany za konieczny, obalony przez 200 tys. scenariuszy) i wtedy
uczciwe rozliczenie było najciekawszą częścią.

### 4.5 Testy

| Test | Jaki realny failure mode wyłapuje |
|---|---|
| `droppedContentBlockKeepsIdentityAndLength` | Blok po porzuceniu treści przestaje się indeksować — pozycje kursora idą w rozsypkę |
| `gcReclaimsRunLengthTextWithAnchors` | **Cel całego checkpointu.** Bez tego nie ma po co go robić |
| `naiveAndIndexedProduceIdenticalDocuments` (istniejący) | Agregaty treapa nie zaktualizowane po zmianie źródła `length()` |
| `snapshotV2IsIgnoredNotMigrated` | Snapshot w starym formacie zostaje odczytany „jakoś" i cicho psuje stan |
| `garbageCollectionSparesMarkAnchors` (istniejący) | Regresja ochrony kotwic — test już raz w tym projekcie **nie sprawdzał tego, co deklarował**, więc weryfikacja mutacją obowiązkowa |
| konformancja Java ↔ TS | Bliźniak w TypeScripcie nie dostał tej samej zmiany |

---

## 5. CP19 — Bramka regresji + automatyzacja bezpieczeństwa (1–1,5 dnia)

### 5.1 Liczby w README zgniją po cichu

PLAN-v2 §9 wymienia to jako *„do rozważenia: job CI porównujący harness z zapisanym wynikiem"*.
Niezrobione — a projekt, którego cała teza brzmi „mierz, nie deklaruj", ma dziś **dwadzieścia
kilka liczb w README i nic, co pilnuje, żeby przestały być prawdziwe.** Pierwsza zmiana, która
je pogorszy, przejdzie niezauważona, i README stanie się dokładnie tym, przed czym ostrzega
`CLAUDE.md`: dokumentacją odtwarzaną z pamięci.

Do zrobienia: baseline w repo (plik, nie stan w usłudze — tak samo jak dashboard Grafany),
job nocny uruchamiający `LoadHarnessIT`, porównanie i **wywalenie builda przy regresji
powyżej progu**.

**Próg musi być uzasadniony, nie zgadnięty**, inaczej łamie zasadę z `CLAUDE.md` o domyślnych
configach bez komentarza. Runner GitHuba jest współdzielony i zmienny — `nightly.yml` sam
to mówi o benchmarkach JMH (*„jego wyniki nie są porównywalne między dniami w sposób, który
da się obronić"*). Wniosek: **próg wyprowadzić z rozrzutu zmierzonego na runnerze**, nie
z liczby wziętej z sufitu. Konkretnie — dziesięć przebiegów baseline'u, odchylenie standardowe,
próg powyżej rozrzutu. Jeśli rozrzut okaże się tak duży, że próg musiałby wynosić 100 %,
**bramka nie ma sensu na runnerze i trzeba to napisać** zamiast wstawiać martwy job.

To jest realne ryzyko tego checkpointu, nie hipotetyczne — i dlatego estymata jest niska:
możliwe, że wynikiem będzie „zmierzone, nie da się, oto dlaczego". To też jest wynik.

### 5.2 Automatyzacja bezpieczeństwa

Brak Dependabota, brak CodeQL, brak skanowania zależności. Dla projektu, którego narracja
zawiera **dwie naprawione dziury w kontroli dostępu**, jest to luka kosztująca kilka godzin
i domykająca wątek, który już jest opowiedziany.

- `dependabot.yml` — Maven, npm, GitHub Actions;
- CodeQL dla Javy i TypeScriptu;
- sprawdzenie, czy jest co naprawiać w obecnych zależnościach (Spring Boot 3.5.0,
  React 19, Vite 8).

Bez ADR-a — to jest konwencja CI, nie decyzja architektoniczna. Uzasadnienie progów
i wyborów idzie w komentarzach w plikach workflow, tak jak w `ci.yml` i `nightly.yml`.

### 5.3 Testy

Ten checkpoint testów nie dodaje — **on sam jest testem.** Weryfikacja polega na sprawdzeniu,
że bramka faktycznie zapala się na sztucznie wprowadzonej regresji (np. `Thread.sleep(5)`
w ścieżce scalania). Bramka, która nigdy nie zapaliła się na znanej regresji, jest tym samym,
czym była klasa `*Measurement`, która się nie uruchamiała: zielonym buildem bez zawartości.

---

## 6. CP20 — Drzewo przedziałowe w `MarkSet` + zaległe długi (2–3 dni)

### 6.1 `MarkSet` — O(m²) zmierzone, nie podejrzewane

`MarkSet.java:36-42` sam dokumentuje własną złożoność, a CP15 ją zmierzył: **0,6 ms przy
100 znacznikach, 5,3 ms przy 1600** (dokument 50 tys. znaków). Wołane **przy każdym
naciśnięciu klawisza**, więc ostatni wiersz to widoczne opóźnienie. Granica użyteczności:
setki znaczników na dokument.

Rozwiązanie jest nazwane w kodzie i w README: **zamiatanie z kopcem aktywnych zakresów,
`O(m log m)`**, docelowo drzewo przedziałowe przeliczane przyrostowo. Zacząć od zamiatania —
jest tańsze, a różnicę widać w tym samym pomiarze.

**Warunek zamknięcia:** ta sama tabela pomiarowa co w CP15, kolumna „po CP20". Bez niej
ten checkpoint jest optymalizacją bez dowodu, czyli dokładnie tym, czego ten projekt unika.

### 6.2 `retry-after` przy limicie tempa — poprawia liczbę, która JUŻ jest w README

Serwer (`DocumentAccessInterceptor.notifyRateLimited`) odsyła `RATE_LIMITED` **bez informacji,
jak długo czekać**. Klient (`connection.ts`, `scheduleRetryAfterThrottling`) czeka
**na sztywno 1000 ms**, i README nazywa to wprost jako przyczynę grubych ogonów:

> *„Ogony (p99) są tu wyraźnie grubsze niż w poprzednim pomiarze i jest to CENA OGRANICZANIA
> TEMPA (…) odrzucone operacje są ponawiane po sekundzie, i ta sekunda jest tym, co widać w p99."*

`RateLimiter.Bucket` **zna dokładny czas do następnego żetonu**: `(1.0 - tokens) / refillPerMs`
milisekund. Wystarczy odesłać tę liczbę w `ErrorMessage` i uszanować ją po stronie klienta
zamiast stałej.

To jest najtańsza pozycja w całym planie z najlepszym stosunkiem wysiłku do efektu:
**poprawia liczbę, która jest już opublikowana** (p99 1299 ms przy 50 klientach i dokumencie
100 tys. znaków), zamiast dodawać nową. Zachowanie pozostaje poprawne — nic nie ginie — ale
czas oczekiwania przestaje być zgadnięty.

**Do zweryfikowania:** czy p99 faktycznie spada. Jeśli nie, znaczy że sekunda nie była
dominującym składnikiem ogona i README twierdzi coś, czego nie zmierzono do końca — co samo
w sobie jest wynikiem wartym zapisania.

### 6.3 Pozostałe długi z README

Uporządkowane wg tego, ile realnie kosztują użytkownika:

| Dług | Gdzie | Co zrobić |
|---|---|---|
| **Klient odtwarza cały log przy otwarciu** | README, „Infrastruktura" | Serwer używa snapshotów wewnętrznie; klient ma dostawać zserializowany snapshot. To jest ta pozycja, która **boli prawdziwego użytkownika** przy dużym dokumencie |
| **`SYNC_CHUNK` liczony w operacjach, nie w bajtach** | `DocumentStompController:209` | Ścieżka rozgłoszeń przeszła na budżet bajtowy w CP10, resynchronizacja nie. Wklejenie dużego fragmentu to **jedna** operacja o dziesiątkach kilobajtów |
| **Presence lokalne dla instancji** | README | Redis już jest. Osobny kanał + wygasanie wpisów. Widoczna dziura produktowa: osoba na innej instancji jest niewidzialna |
| **Indeks klienta O(n)** | README | Port treapa jest mechaniczny — **ciekawa jest brakująca połowa: pomiar w przeglądarce.** Bez niego to optymalizacja w ciemno, czyli to, czego projekt nie robi |
| `App.tsx` — 655 linii | `frontend/src/App.tsx` | Jedyne miejsce, gdzie jakość kodu widocznie siada wobec backendu |

**Ten checkpoint jest celowo ostatni i celowo podzielny.** Jeśli budżet się skończy, ucina się
go od dołu bez szkody dla reszty planu — w odróżnieniu od CP18.

---

## 7. Estymata

| Checkpoint | Dni |
|---|---:|
| §0 — Dotarcie | 1,5–2 |
| CP16 — Autoryzacja strukturalnie | 2–2,5 |
| CP17 — Deterministyczna symulacja | 4–6 |
| CP18 — Blok bez treści | 3–5 |
| CP19 — Bramka regresji + bezpieczeństwo | 1–1,5 |
| CP20 — `MarkSet` + długi | 2–3 |
| **Razem** | **13,5–20 dni roboczych** |

Dla porównania: v1 ≈ 13–17 dni, v2 ≈ 15,5–21 dni. **v3 jest trzecim projektem tej samej
wielkości** — z tą różnicą, że nie dokłada ani jednej funkcji. Warto to wiedzieć przed startem:
plan, w którym nie przybywa nic widocznego, jest trudniejszy do doprowadzenia do końca
niż plan, w którym co dwa dni działa coś nowego.

**Jeśli budżet wynosi jedną pozycję:** §0. **Jeśli jedną inżynierską:** CP17.

---

## 8. Ryzyka

| Ryzyko | Prawdopodobieństwo | Przeciwdziałanie |
|---|---|---|
| **Refaktor autoryzacji wprowadza regresję dostępu** — czyli CP16 otwiera dokładnie to, co ma zamknąć | Średnie | `PermissionIT` przechodzi **przed i po**, bez zmian w samych testach. Weryfikacja mutacją po refaktorze, nie przed |
| **CP17 przeciąga się** — scheduler deterministyczny to najtrudniejsza rzecz w tym planie | **Wysokie** | Zamknąć na JEDNYM scenariuszu (partycja Redisa) z pełnym determinizmem. Scenariusze są tanie, scheduler drogi. To samo przeciwdziałanie, co przy CP13 w v2 |
| **Symulacja okazuje się niedeterministyczna** i nikt tego nie zauważa, bo testy przechodzą | Średnie | `sameSeedProducesIdenticalEventLog` **przed** pierwszym scenariuszem, nie po. Bez niego cały checkpoint jest ładniejszym testem chaosu |
| **CP18 rozjeżdża Javę z TypeScriptem** | Średnie | Korpus konformancyjny + istniejący job CI. Mechanizm sprawdzony dwukrotnie (v1, CP13) |
| **Bramka regresji nie da się obronić na runnerze** (rozrzut większy od progu) | Średnie | Zmierzyć rozrzut PRZED wstawieniem bramki. Wynik „nie da się, oto dlaczego" jest akceptowalnym zamknięciem CP19 — martwy job nie jest |
| **Hosting usypia i pierwsze wrażenie jest gorsze niż brak linku** | Średnie | Rozstrzygnąć w §0.1 przed publikacją linku, nie po. GIF-y zostają jako plan B |
| **Publiczna instancja z domyślnym sekretem tokenu** | Niskie, skutek wysoki | §0.1 pkt 3 — sekret ze zmiennej środowiskowej. Do sprawdzenia **przed** pierwszym deployem |
| **Plan bez widocznych efektów traci napęd** w połowie | Średnie | §0 daje efekt widoczny w pierwszym tygodniu. CP20 jest podzielny i można go użyć jako odskoczni |

---

## 9. Czego ten plan świadomie NIE obejmuje

- **Model checking (TLA+ / Stateright).** Kuszące i tematycznie trafione — README opowiada
  historię *„200 tys. losowych scenariuszy pokazało, że warunek nie był konieczny"*, czyli
  wniosek z **próbkowania**, na który model checker odpowiada wyczerpująco. Odrzucone,
  bo **odpowiada na tę samą klasę pytań co CP17**, a przy tym budżecie robienie obu jest
  nadmiarowe. DST wygrywa, bo obejmuje **cały system rozproszony** (transport, persystencja,
  pady instancji), a nie samą regułę scalania, i bo połowa jego klocków już leży w repo.
  **Najmocniejszy kandydat na v4** — i jedyna pozycja z tej listy, o której tak myślę.
- **Fugue zamiast RGA.** Anomalia przeplotu jest udokumentowana i utrwalona testem
  (`RgaInterleavingTest`). Przepisanie rdzenia zamienia mocne „rozumiem kompromis i umiem
  go nazwać" na słabsze „mam nieco lepszy algorytm", za cenę tygodni. Bez zmian względem v2.
- **Jedna implementacja skompilowana do WebAssembly.** Zlikwidowałaby dług dwóch implementacji,
  ale korpus konformancyjny **już dziś obsługuje ryzyko**, które WASM by usunął — a koszt
  jest rzędu całego planu.
- **Ekran zarządzania dostępem i tabela zaproszeń.** Wymienione w audycie v2 jako niezrobione.
  `grant`/`revoke`/`setVisibility` istnieją i są przetestowane; brakuje im wyłącznie ekranu.
  To jest praca UI bez zawartości inżynierskiej — czyli dokładnie to, co `CLAUDE.md` nazywa
  nadmiarowym zakresem. Zostaje w README jako granica.
- **Funkcje produktowe** — powiadomienia, wzmianki, eksport, wersje mobilne, osadzone obiekty,
  undo świadome CRDT, edycja offline komentarzy. PLAN-v2 §10 odrzucił je z uzasadnieniem,
  które nie przestało obowiązywać. v3 nie jest planem produktowym.
- **Rozdzielenie stanowiska pomiarowego na osobne maszyny.** README nazywa tę granicę
  (klienci i serwer w jednym procesie, latencja sieci ≈ 0). Nadal poza zakresem — po CP10
  nie jest już potrzebne do odpowiedzi na żadne otwarte pytanie, a samo w sobie niczego
  nie demonstruje.

---

## 10. Audyt końcowy — po CP20

Przejście punkt po punkcie **przeciw kodowi**, nie przeciw pamięci. W v2 takie przejście
znalazło `HistoryController` bez żadnej autoryzacji; tym razem znalazło czwartą dziurę tej
samej klasy, jeszcze zanim ruszył pierwszy checkpoint — i to jest najlepszy argument, że ta
sekcja nie jest formalnością.

### 10.1 Niezrobione — i to jest najważniejsza część tego audytu

| Punkt | Stan | Dlaczego |
|---|---|---|
| **§0.1 Hosting** | **niezrobione** | Wymaga konta na Fly.io/Railway i decyzji o planie płatnym. Zrobione zostało to, co dało się zrobić w repo: sekret tokenu przestał mieć wartość domyślną, więc instancja publiczna nie wystartuje z kluczem znanym z repozytorium |
| **§0.2 Migracja na angielski** | **zrobione w ~15 %** | Decyzja zapisana, cała nowa treść po angielsku (kod CP16–CP20, ADR-0012, ADR-0013, komunikaty commitów). **README, ADR 0001–0011 i istniejące komentarze zostają po polsku.** To jest dokładnie stan, przed którym ostrzega §0.2: repozytorium dwujęzyczne, czyli gorsze niż w każdym z języków osobno |
| **§0.3 Kondensacja README** | **niezrobione** | README urosło, nie skurczyło się — doszły sekcje o CP18 i CP20 oraz sprostowanie do p99 |
| **CP20 — snapshot CRDT do klienta** | **niezrobione** | Największy pozostały dług produktowy: klient nadal odtwarza cały log przy otwarciu |
| **CP20 — `SYNC_CHUNK` w bajtach** | **niezrobione** | |
| **CP20 — presence w klastrze** | **niezrobione** | |
| **CP20 — pomiar indeksu w przeglądarce** | **niezrobione** | |
| **CP20 — `App.tsx` (655 linii)** | **niezrobione** | |
| **Testy e2e po zmianach** | **nieuruchomione** | Port 8080 zajmowała instancja uruchomiona przez autora. Uruchomienie wymagałoby albo jej zatrzymania, albo testowania nieaktualnego builda — pierwsze jest ingerencją w cudzą pracę, drugie dałoby wynik mylący. CI uruchamia e2e przy każdym pull requeście |

**Punkt kontrolny z §0.2 zadziałał i wypadł negatywnie.** Sekcja mówi: *„jeśli po CP20 wiersze
1–3 nie są zrobione, lepiej wrócić do polskiego w całości niż zostawić repozytorium w połowie
drogi"*. Nie są zrobione. Decyzja należy do autora, ale plan przewidział ten moment i wskazał
wyjście — zostawienie tego bez rozstrzygnięcia jest gorsze niż każda z dwóch odpowiedzi.

### 10.2 Zrobione inaczej, niż zakładał plan — świadomie

| Punkt planu | Jak jest | Dlaczego |
|---|---|---|
| CP16: „filtr albo `@PreAuthorize` na `/api/documents/{id}/**`" | **Trzy adnotacje przy endpointach + strażnik odmawiający obsługi niezadeklarowanego** | Reguła ścieżkowa nie obejmuje `/api/comments/{threadId}/…` i nie potrafi wyrazić, że przywracanie wymaga ZAPISU, a podgląd ODCZYTU — a pomylenie tych dwóch to dokładnie sposób, w jaki endpoint przywracania stał otworem dla czytelników |
| CP16: „test ArchUnit" | **Refleksja, bez nowej zależności** | Cała reguła to jedno przejście po metodach. Ten sam kompromis, który `TokenService` robi wobec biblioteki JWT |
| CP16: dwie adnotacje | **Trzy** | Znalazły się endpointy, które TWORZĄ dokument: nie ma czego autoryzować, ale anonimowy wywołujący nie może ich uruchomić. Zwinięcie tego do „publiczny" zostawiłoby obie dziury otwarte, wyglądając na decyzję |
| CP17: „symulacja obejmuje cały system rozproszony" | **Model warstwy rozproszonej na PRAWDZIWYM algorytmie scalania** | Nie dotyka Springa, Postgresa ani gniazda. Granica nazwana w ADR-0013; `ClusterChaosIT` zostaje |
| CP19: „bramka porównująca harness z baseline'em" | **Bramka na KSZTAŁT złożoności, nie na milisekundy** | Zmierzone: rozrzut tego stanowiska (28 ms kontra 43 ms na tym samym kodzie) przekracza regresję, którą taka bramka miałaby wykrywać. Plan przewidywał ten wynik jako dopuszczalny — §5.1 mówi wprost „jeśli rozrzut okaże się za duży, trzeba to napisać zamiast wstawiać martwy job" |
| CP20: `retry-after` | **Zrobione, i NIE dało tego, co miało dać** | Patrz 10.4 |

### 10.3 Znalezione audytem i przeglądem — naprawione

| Co | Gdzie |
|---|---|
| **Czwarta dziura w kontroli dostępu**: `POST /documents/recovered` z wygasłym tokenem dawał dokument bez właściciela i z widocznością `link` | §2.3, naprawione w CP16 |
| **Ta sama klasa w `POST /documents`** — dokument bez właściciela, czyli niemożliwy do zabezpieczenia przez kogokolwiek | CP16 |
| **Sekret tokenu z wartością domyślną w repo** — pełne obejście uwierzytelnienia, ciche | CP16 |
| Metryka `crdt.gc.removed.characters.total` po CP18 liczyła coś innego, niż mówi nazwa | przegląd końcowy |
| Log GC mówił „usunięto" przy niezmienionej liczbie bloków | przegląd końcowy |
| `Block.append` na bloku bez treści wkleiłby do dokumentu napis `null` i zawyżył długość o cztery | przegląd końcowy |
| `throttleRounds` w kliencie rósł bez ograniczenia i nie zerował się przy ponownym połączeniu; przy `advertised == 0` dałoby to `setTimeout(fn, NaN)`, czyli pętlę odpytywania | przegląd końcowy |
| Martwa stała `DENIED` w `CommentController` | przegląd końcowy |

### 10.4 Czego pomiar NIE potwierdził

**`retry-after` nie poprawił p99, a sam z siebie go pogorszył.** Trzy przebiegi harnessu jeden
po drugim, 100 tys. znaków / 50 klientów:

| Wariant | p99 | odrzuceń |
|---|---:|---:|
| przed (stała 1 s) | 872 ms | 1523 |
| sam `retry-after` | **1352 ms** | 1614 |
| `retry-after` + wydłużanie wykładnicze | 826 ms | 1368 |

Teza z README — *„odrzucone operacje są ponawiane po sekundzie, i ta sekunda jest tym, co widać
w p99"* — jest **nieprawdziwa**. Ogon wyznacza tempo napełniania wiadra razy rozmiar
zakolejkowanej paczki; klient z N operacjami za pustym wiadrem czeka N/60 sekundy niezależnie
od tego, jak często pyta. Ponawianie co 17 ms zamiast co sekundę to odpytywanie w pętli.

Zmiana zostaje, bo znika zgadnięta stała, a odmów jest o ~10 % mniej — ale **nie jest to zysk
latencji i commit tego nie udaje**.

**CP17 nie znalazł ani jednego błędu w systemie.** Znalazł trzy w samym sobie. `REGRESSION_SEEDS`
jest puste i opisane jako puste. Maszyna jest zbudowana i sprawdzona mutacją (odwrócenie warunku
skanu RGA wywala 593 z 806 testów, z kontrprzykładem na czterech operacjach), ale na razie nie
zarobiła na siebie prawdziwą wadą.

### 10.5 Zmierzone przy okazji, wcześniej nieznane

**`ClusterChaosIT` jest flakiem zależnym od obciążenia, i teraz jest to liczba.** W izolacji
**6 przebiegów na 6** zielonych; w pełnym buildzie padł w **2 z 4**. Nie jest to regresja
wprowadzona przez v3 — padał tak samo przed CP17. Jest to natomiast dokładnie ten failure mode,
dla którego CP17 powstał: awaria zależna od uszeregowania wątków, nieodtwarzalna, znikająca
przy próbie zbadania. Nocny job `chaos ×10` nadal jej szuka; symulacja pokrywa tę samą klasę
błędów odtwarzalnie, ale nie dotyka prawdziwego Redisa.

### 10.6 Uchybienie w prowadzeniu historii

Commit CP17 (`a6908f2`) wciągnął `.github/dependabot.yml` i `.github/workflows/codeql.yml`,
o których jego opis nie wspomina. `CLAUDE.md` §2 wymaga jednej logicznej zmiany na commit.
Historia nie została przepisana, bo autor pracował równolegle na tej samej gałęzi i rebase
zniszczyłby mu stan roboczy — ale uchybienie jest uchybieniem i lepiej stoi tutaj wypisane
niż nieznalezione.

