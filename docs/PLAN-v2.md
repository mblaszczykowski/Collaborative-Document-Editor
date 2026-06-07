# Plan v2 — z artefaktu w produkt

> **Status: CP10 zamknięty, CP11 w trakcie.** Następca [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md),
> który opisuje checkpointy CP1–CP9 (wszystkie zamknięte). Ten dokument opisuje CP10–CP15.
>
> | Checkpoint | Stan | Uwagi |
> |---|---|---|
> | CP10 — Fundament | **zamknięty** | Otwarte pytanie z CP8 rozstrzygnięte — patrz niżej |
> | CP11 — Multi-dokument | **w trakcie** | Zrobione: odsiew klastra, limit pamięci, schemat V2. Zostało: REST, lista i routing we froncie |
> | CP12 — Uprawnienia | niezaczęty | |
> | CP13 — Rich text | niezaczęty | |
> | CP14 — Komentarze | niezaczęty | |
> | CP15 — Pomiar + dokumentacja | niezaczęty | |
>
> **Najważniejszy wynik CP10, którego plan nie przewidywał.** Punkt 2.1 zakładał, że
> brak powtarzalnej zbieżności powyżej ~10 klientów jest problemem systemu i że sklejanie
> rozgłoszeń go naprawi. Sklejanie owszem pomogło (2,7× szybsza zbieżność przy 50 klientach),
> ale **przyczyną braku zbieżności było stanowisko pomiarowe**: klient testowy miał domyślny
> bufor JSR-356 o rozmiarze 8 KiB, który przepuszczał rozgłoszenia (~70 B na ramkę),
> a zatrzymywał odpowiedzi na resynchronizację (300 operacji ≈ 15 KB) — czyli jedyny
> mechanizm zdolny naprawić zgubioną operację. Szczegóły w README i ADR-0002.
>
> Hipoteza z planu o przepełnieniu bufora wysyłkowego serwera **nie potwierdziła się**:
> metryka `ws_sessions_closed_total{reason}` nie odnotowała ani jednego takiego zamknięcia
> przy 50 klientach.

Wersja 1 była **wąskim artefaktem inżynierskim**: jeden dokument, jeden problem (zbieżność
danych rozproszonych), maksymalna głębia. Zakres produktowy był świadomie odrzucony
w [ADR-0007](adr/0007-zakres-ui.md), a README zamyka się sekcją „co zrobiłbym inaczej
w produkcji", która wymienia dokładnie te cztery braki, które ten plan teraz adresuje.

Wersja 2 zmienia pozycjonowanie: **edytor tekstu bogatego z komentarzami, wieloma dokumentami
i modelem uprawnień.** To jest inny projekt niż v1 — nie rozszerzenie, tylko zmiana tezy.

Zasady prowadzenia dokumentu przejęte z v1 bez zmian: jeśli decyzja z planu okaże się zła
w praktyce, poprawiamy **tutaj** i commitujemy zmianę planu razem z powodem. Historia
iteracji jest częścią artefaktu.

---

## 0. Zmiana pozycjonowania — zanim ruszy CP10

Repozytorium w tej chwili **wprost odradza** zakres, który ten plan wprowadza. Bez
uporządkowania tego, dokumentacja będzie sama ze sobą sprzeczna, a to kosztuje więcej niż
brak dokumentacji — czytelnik przestaje ufać całości.

| Plik | Co mówi teraz | Co zrobić |
|---|---|---|
| `CLAUDE.md`, „Czego unikać" | „Nadmiarowego scope'u (rich text formatting, komentarze, multi-dokument workspace) kosztem głębi w core problemie" | Przepisać: zakres rozszerzony świadomie po zamknięciu CP9, z warunkiem utrzymania głębi rdzenia |
| [ADR-0007](adr/0007-zakres-ui.md) | Zakres funkcjonalny mockupu odrzucony | Status → **Zastąpiony przez ADR-0008**. Nie kasować — decyzja była słuszna dla v1 |
| README, „Co zrobiłbym inaczej" | Wymienia te funkcje jako świadome granice | Rozdzielić na „zrobione w v2" i „nadal poza zakresem" |

### ADR-0008 — rozszerzenie zakresu do produktu

Pierwsza rzecz do napisania, przed jakimkolwiek kodem. Układ jak w pozostałych ADR-ach.

**Kontekst do opisania:** v1 osiągnęła swój cel (rdzeń CRDT udowodniony property testami,
zbieżność zmierzona, granice nazwane). Wąski zakres przestał być atutem w momencie, w którym
rdzeń jest gotowy — dalsza głębia w tym samym miejscu ma malejący zwrot, a cztery odrzucone
funkcje są naturalnym kierunkiem, w którym ten rdzeń da się *wykorzystać*, a nie tylko pokazać.

**Rozważane opcje:** (A) zostać przy v1 i pogłębiać rdzeń — Fugue, undo świadome CRDT;
(B) rozszerzyć do produktu; (C) rozszerzyć wybiórczo — tylko multi-dokument i uprawnienia,
bez rich textu.

**Konsekwencje negatywne — do wpisania szczerze, bo są realne:**
- **Rozcieńczenie.** Projekt przestaje mieć jedno zdanie opisu. Recenzent z trzema minutami
  może wyjść z „kolejny klon Google Docs" zamiast „ktoś naprawdę rozumie CRDT".
  Przeciwdziałanie: README musi nadal otwierać się rdzeniem, a nie listą funkcji.
- **Podwójny koszt każdej zmiany algorytmu** (Java + TypeScript) dotyczy teraz także znaczników
  formatowania. Dług opisany w README rośnie proporcjonalnie do nowego zakresu.
- **GC tombstone'ów staje się jeszcze mniej skuteczny** — patrz CP13, sekcja o kotwicach.
  To jest najpoważniejsza konsekwencja techniczna i może wymusić przeprojektowanie
  reprezentacji bloku, które [ADR-0005](adr/0005-tombstone-gc.md) odkładał.
- **Wszystkie liczby w README stają się nieaktualne.** Rich text zmienia profil ruchu.
  Pozostawienie starych liczb byłoby nieuczciwe — stąd CP15 jest obowiązkowy, nie opcjonalny.

**Do zweryfikowania w CP15:** czy po rozszerzeniu projekt nadal czyta się jako jedna spójna
całość, czy jako dwie sklejone.

---

## 1. Kolejność i dlaczego akurat taka

Zależności nie są dowolne — trzy z nich są twarde:

```
CP10  Fundament ────────────┐
      (propagacja, ruch,    │  rich text i komentarze ZWIĘKSZAJĄ ruch.
       uruchamialność)      │  Budowanie ich na niezdiagnozowanym
                            │  problemie propagacji >10 klientów
                            │  oznacza debugowanie dwóch rzeczy naraz.
                            ▼
CP11  Multi-dokument ───────┐
      (kontener)            │  uprawnienia bez wielu dokumentów nie mają
                            │  czego chronić — jeden wspólny dokument to
                            │  model dostępu „wszyscy wszystko".
                            ▼
CP12  Uprawnienia ──────────┐
      (autoryzacja per      │  autoryzacja musi objąć TYPY operacji.
       destynacja STOMP)    │  Dołożenie znaczników formatowania po
                            │  zbudowaniu autoryzacji = jedno miejsce
                            │  do rozszerzenia. Odwrotnie = przerabianie.
                            ▼
CP13  Rich text ────────────┐
      (rdzeń algorytmiczny) │  komentarz to zakotwiczony zakres.
                            │  Warstwa kotwic powstaje w CP13 —
                            │  komentarze ją tylko REUŻYWAJĄ.
                            ▼
CP14  Komentarze
                            │
                            ▼
CP15  Ponowny pomiar + dokumentacja
```

Kluczowa obserwacja: **komentarze są tanie, ale tylko po rich texcie.** Zrobione wcześniej
wymagałyby własnej warstwy kotwic, którą CP13 i tak by zastąpił. To jest jedyny powód,
dla którego komentarze — funkcja produktowo prostsza — są w planie później niż rich text.

---

## 2. CP10 — Fundament (2–3 dni)

Nie jest to „sprzątanie przed właściwą pracą". Trzy z tych rzeczy są **warunkiem** sensownego
wykonania CP11–CP14.

### 2.1 Domknąć otwarte pytanie o propagację

README kończy sekcję wydajności zdaniem „nie wiem, czy przyczyna leży w systemie, czy
w stanowisku pomiarowym". Rich text i komentarze **zwielokrotnią ruch** — startowanie
z niezdiagnozowanym problemem oznacza, że każda przyszła regresja będzie nierozróżnialna
od tej istniejącej.

Hipoteza do sprawdzenia w pierwszej kolejności: **ścieżka rozgłoszenia nie jest wsadowana,
w przeciwieństwie do ścieżki zapisu.**

`DocumentService.java:150` — `onFastPath.accept(documentId, List.of(op))`. Jedna ramka na
naciśnięcie klawisza, rozesłana do N subskrybentów. Zapis (`flush()`) i potwierdzenia są
wsadowane co 50 ms; rozgłoszenie nie.

Arytmetyka dla scenariusza z harnessu (50 klientów × 40 operacji):

| Wielkość | Wartość |
|---|---:|
| Ramek na topicu `/ops` | 2000 × 50 = **100 000** |
| Kolejka wysyłkowa na klienta | ~2000 × ~200 B ≈ **400 KB** |
| `send-buffer-size-bytes` (`application.yml:55`) | **512 KiB** |

Po przepełnieniu bufora sesja jest zamykana — co daje dokładnie objaw z README: serwer ma
komplet operacji, część klientów zostaje trwale w tyle, w logu nie ma błędu.

Kroki:

1. **Zmierzyć przed naprawą.** Licznik sesji zamkniętych przez przepełnienie bufora
   w `DocumentMetrics` + panel w dashboardzie. Jeśli zapala się przy 25 klientach i milczy
   przy 10 — pytanie jest odpowiedziane liczbą. To jest wynik sam w sobie, niezależnie
   od tego, czy hipoteza się potwierdzi.
2. **Skleić rozgłoszenia w okno ~10–15 ms**, tym samym kształtem co `flushAll()`.
   Wymaga aktualizacji [ADR-0002](adr/0002-transport.md) i [ADR-0004](adr/0004-skalowanie-poziome.md),
   bo ADR-0004 mówi „rozgłoszenie natychmiast" i to przestanie być dosłownie prawdą.
   Koszt: ≤15 ms do latencji przy zmierzonym p50 22–553 ms.
3. **Rozdzielić klientów od serwera** w harnessie — README sam wskazuje to jako warunek
   ostatecznego rozstrzygnięcia „system czy stanowisko".

**Kryterium zamknięcia:** scenariusz 25 i 50 klientów zbiega się powtarzalnie w pięciu
kolejnych przebiegach, albo istnieje metryka i opis mówiące, dlaczego nie.

### 2.2 Presence — O(N²) bez throttlingu

`Editor.tsx:82` — `reportCursor` odpala się na każdym `selectionSet || docChanged`, czyli na
każdym klawiszu i każdej strzałce. `PresenceService.java:79` rozsyła w odpowiedzi **pełną listę
uczestników wszystkim** w dokumencie. Bez throttlingu, bez delty.

10 osób piszących po 5 znaków/s = 50 wiadomości/s do serwera → 500 wysyłek/s, każda z pełną
tablicą JSON.

Dodatkowo: `LoadHarnessIT` **w ogóle nie generuje ruchu presence** (`TestStompClient` go nie
wysyła). Liczby w README opisują więc system lżejszy niż ten, którego używa przeglądarka.

- Throttle po stronie klienta: ~50–100 ms, trailing edge.
- Rozgłaszanie zmienionego uczestnika, nie całej listy.
- Dodanie ruchu presence do harnessu — inaczej liczby z CP15 powtórzą ten sam błąd.

Po CP12 dochodzi tu drugi wymiar: presence musi respektować uprawnienia (viewer widzi kursory,
ale czy jego kursor widzą inni — decyzja produktowa do podjęcia w CP12).

### 2.3 Uruchamialność jedną komendą

Nie ma `Dockerfile`, a `docker-compose.yml` w nagłówku obiecuje „Uruchomienie pełnego stacku:
`docker compose up -d`", podczas gdy zawiera wyłącznie postgres, redis i profil observability.

Przy czterech nowych funkcjach do zademonstrowania to przestaje być kosmetyką — bez tego
nikt tych funkcji nie zobaczy.

- Multi-stage `Dockerfile` dla `app` (warstwowy jar), `Dockerfile` + nginx dla `frontend`.
- Usługi `app` i `frontend` w compose; poprawienie mylącego nagłówka.
- Docelowo: hostowana instancja i link nad GIF-em w README.

### 2.4 Dług, który zaprzecza dokumentacji

Drobne, ale w repo o tej staranności rzucają się w oczy — i wszystkie są sprzecznością
między kodem a jego własnym opisem.

| Miejsce | Problem |
|---|---|
| `application.yml:67` → `CollabEditProperties:52` | `snapshot-after-idle-ms` zadeklarowane i uzasadnione komentarzem, **nigdzie nieczytane**. `maybeSnapshot()` sprawdza wyłącznie próg liczby operacji |
| `application.yml:63` | `op-batch-max-size: 100` — to samo. `drainUnwritten()` zabiera cały bufor niezależnie od rozmiaru; zadeklarowany limit nie istnieje |
| `application.yml:13`, `:65`, `OperationStore.java:52` | Martwe „do zweryfikowania w CP8" — CP8 zamknięty. Reguła z `docs/adr/README.md` każe wrócić po checkpoincie |
| `.github/workflows/nightly.yml` | Job chaosu `if: false` z `echo "Do włączenia w CP8"`, mimo że `ClusterChaosIT` istnieje i README się na niego powołuje |
| `.gitignore` | Reguła celuje w `demo/recordings/`, a nagrania leżą w `frontend/demo-recordings/` — 1,5 MB `.webm` śledzone w gicie |
| `TokenService.java:78` | Ładunek sklejany `String.join("|")`, `verify` robi `split("\\|")` i odrzuca przy `parts.length != 4`. `displayName` pochodzi od użytkownika (`DocumentRestController.java:66`). Nazwa z `\|` → token wydany, ale CONNECT go odrzuca: logowanie „działa", aplikacja nie. Zawodzi bezpiecznie, ale to realny błąd. Brak też limitu długości nazwy |
| `App.tsx` | 732 linie, 10 komponentów. Reszta repo trzyma wzorową dyscyplinę plików; CP11 i tak doda tu routing i listę dokumentów, więc podział wykonać **przed**, nie po |

Fully-kwalifikowane nazwy pakietów zamiast importów: `PresenceService.java:37`,
`DocumentService.java:263`, `DocumentStompController.java:214`.

---

## 3. CP11 — Multi-dokument (2–3 dni)

### 3.1 Model danych

Migracja `V2__workspaces.sql`:

```
workspaces          id, name, owner_id, created_at
documents           + workspace_id (FK), + deleted_at (soft delete),
                    + preview_text (materializowany przy snapshocie)
```

`preview_text` jest **cachem, nie źródłem prawdy** — dokładnie jak snapshoty w
[ADR-0003](adr/0003-historia-zmian.md). Lista dokumentów nie może ładować replik CRDT
do pamięci tylko po to, żeby pokazać trzy pierwsze linijki; to jest ta sama pułapka,
przed którą chroni istniejący test „skasowanie wszystkich snapshotów nic nie zmienia".

Soft delete zamiast `DELETE`, bo `operations` ma `ON DELETE CASCADE` — twarde usunięcie
dokumentu skasowałoby log operacji, czyli źródło prawdy. To jest sprzeczne z zasadą
z `V1__initial_schema.sql` („tabela wyłącznie dopisywana"). Dokument usunięty ma być
niedostępny, nie nieodwracalnie zniszczony.

### 3.2 Dwa realne problemy skalowania, które multi-dokument odsłania

**(a) Każda instancja deserializuje ruch wszystkich dokumentów.**

`RedisConfig` subskrybuje wzorzec `collabedit:doc:*:ops` i uzasadnia to tym, że odsiew
dokumentów nietrzymanych w pamięci „jest trywialny". Dla samego sprawdzenia — tak. Ale
`ClusterBus.onMessage:112` robi `readTree(message.getBody())` **przed** jakimkolwiek filtrem;
sprawdzenie `registry.isLoaded()` następuje dopiero w `DocumentService.onClusterMessage:240`.
Przy jednym dokumencie to nie miało znaczenia. Przy tysiącu — każda instancja parsuje JSON
całego ruchu klastra.

Naprawa jest tania i nie wymaga zmiany modelu subskrypcji: identyfikator dokumentu **jest już
w nazwie kanału**. Wystarczy wyciągnąć go z `message.getChannel()` i sprawdzić `isLoaded`
przed parsowaniem. Uzasadnienie w `RedisConfig` staje się wtedy prawdziwe.

**(b) `flushAll()` co 50 ms po wszystkich załadowanych dokumentach.**

`DocumentService.java:167` iteruje `registry.loaded()` i dla każdego woła `drainUnwritten()`,
które bierze blokadę dokumentu. Przy 500 załadowanych dokumentach to 10 000 zajęć blokady
na sekundę, w większości bez żadnej pracy do wykonania.

Naprawa: rejestr utrzymuje zbiór „brudnych" dokumentów (tych z niezapisanymi operacjami),
a `flushAll` iteruje po nim zamiast po wszystkim. Ten sam wzorzec dotyczy `antiEntropySweep`.

### 3.3 Zarządzanie pamięcią

`idle-document-eviction-ms` już istnieje, ale nie ma **górnej granicy** liczby replik
w pamięci. Przy wielu dokumentach potrzebne:

- Limit LRU na `DocumentRegistry` z konfiguracją i uzasadnieniem wartości.
- Metryka: liczba załadowanych replik, szacowana pamięć na replikę, liczba eksmisji/s.
- Eksmisja musi wymusić `flush` przed zwolnieniem — inaczej gubimy niezapisany wsad.

### 3.4 Zakres pozostały

REST: lista, tworzenie, zmiana nazwy, soft delete, przywracanie, wyszukiwanie (Postgres FTS
po `preview_text`). Frontend: routing, lista dokumentów, tworzenie — po podziale `App.tsx`
z CP10.

### 3.5 Testy

| Test | Jaki failure mode wyłapuje |
|---|---|
| `evictionFlushesUnwrittenOperations` | Eksmisja pod presją pamięci gubi wsad — utrata danych widoczna dopiero przy obciążeniu |
| `softDeletedDocumentKeepsOperationLog` | `ON DELETE CASCADE` kasuje źródło prawdy; nieodwracalne |
| `previewTextIsOnlyACache` | Podgląd staje się źródłem prawdy — dokładny odpowiednik istniejącego testu snapshotów |
| `clusterMessageForUnloadedDocumentIsNotParsed` | Regresja odsiewu z 3.2(a); niewidoczna funkcjonalnie, widoczna tylko w profilu CPU |
| `flushTouchesOnlyDirtyDocuments` | Regresja z 3.2(b) — jw. |

---

## 4. CP12 — Uprawnienia (2,5–3,5 dnia)

### 4.1 To jest przede wszystkim praca nad bezpieczeństwem, nie nad UI

Stan obecny: `StompConnectInterceptor` obsługuje **wyłącznie** `CONNECT` i `DISCONNECT`.
Nie ma żadnej autoryzacji na `SUBSCRIBE` ani `SEND`. Tabela `document_members` istnieje
w schemacie od V1, ale **nie jest nigdzie egzekwowana**.

Dziś jest to nieszkodliwe — jest jeden wspólny dokument i model dostępu „wszyscy wszystko".
**W momencie wejścia CP11 staje się to podatnością:** dowolny uwierzytelniony użytkownik może
zasubskrybować `/topic/doc/{dowolne-id}/ops` i czytać cudzy dokument, albo wysłać operację
na `/app/doc/{dowolne-id}/op`.

To jest najważniejszy pojedynczy element tego checkpointu i powód, dla którego CP12 nie może
być przesunięty za CP13.

### 4.2 Model ról

| Rola | Tekst | Formatowanie | Komentarze | Udostępnianie |
|---|:---:|:---:|:---:|:---:|
| `viewer` | — | — | odczyt | — |
| `commenter` | — | — | zapis | — |
| `editor` | ✓ | ✓ | ✓ | — |
| `owner` | ✓ | ✓ | ✓ | ✓ |

Migracja `V3__permissions.sql`: `document_members.role`, `workspace_members` (rola dziedziczona
na dokumenty, nadpisywalna per dokument), `invitations`.

**Rozstrzygnięcie do zapisania w ADR-0009:** dziedziczenie z workspace'u kontra wyłącznie
uprawnienia per dokument. Dziedziczenie jest wygodniejsze produktowo i trudniejsze do
zweryfikowania — sprawdzenie dostępu przestaje być jednym zapytaniem, a unieważnienie cache'a
musi objąć wszystkie dokumenty workspace'u.

### 4.3 Autoryzacja per destynacja STOMP

Rozszerzenie istniejącego `ChannelInterceptor`:

- `SUBSCRIBE` → wyciągnij `documentId` z destynacji, sprawdź prawo odczytu, odrzuć ramkę
  przy braku.
- `SEND` → sprawdź prawo **odpowiednie do typu operacji**, nie tylko do dokumentu.
  `commenter` może wysłać `AddMark(type=comment)`, ale nie `Insert`. To oznacza, że
  autoryzacja musi zajrzeć do treści operacji — po CP13 lista typów rośnie, więc mapowanie
  „rola → dozwolone typy operacji" ma być **jedną tablicą w jednym miejscu**, a nie serią
  `if`-ów rozsianych po interceptorze.
- Cache uprawnień per sesja, inaczej każda operacja to zapytanie do bazy.

**Druga linia obrony w `DocumentService.submit`.** Warstwa STOMP to nie jedyna droga do
zapisu — jest jeszcze przywracanie wersji z historii (`HistoryService`). Autoryzacja
wyłącznie w transporcie jest autoryzacją jednej ścieżki.

### 4.4 Unieważnienie w trakcie sesji

README wymienia to jako znane ograniczenie: token sprawdzany raz, przy `CONNECT`.
Przy uprawnieniach robi się z tego realny problem — odebranie dostępu ma zadziałać
natychmiast, a nie po 12 godzinach (`LIFETIME` w `TokenService`).

Kanał `collabedit:acl:{userId}` w Redisie → instancja unieważnia cache i **aktywnie zrywa
subskrypcje** tego użytkownika na dokumentach, do których stracił dostęp. Klient dostaje
błąd i przechodzi swoją istniejącą ścieżką obsługi (`onError` w `connection.ts`).

### 4.5 Rate limiting

README wymienia brak jako granicę. Naturalnie ląduje w tym samym interceptorze: token bucket
per sesja, z osobnym limitem dla operacji i dla presence (bardzo różne profile). Wartości
uzasadnione liczbowo, nie z palca — zgodnie z regułą z `CLAUDE.md`.

### 4.6 Testy

| Test | Jaki failure mode wyłapuje |
|---|---|
| `subscribeToForeignDocumentIsRejected` | IDOR — odczyt cudzego dokumentu przez subskrypcję topicu. **Najważniejszy test tego checkpointu** |
| `commenterCannotInsertText` | Autoryzacja per dokument zamiast per typ operacji |
| `revokedAccessTerminatesLiveSubscription` | Odebranie dostępu działa dopiero po wygaśnięciu tokenu |
| `historyRestoreChecksWriteAccess` | Autoryzacja tylko w transporcie; obejście drugą ścieżką zapisu |
| `workspaceRoleIsOverriddenByDocumentRole` | Dziedziczenie nadpisuje jawne odebranie dostępu |
| `rateLimitClosesFloodingSession` | Jeden klient wysyca instancję |

---

## 5. CP13 — Rich text (5–7 dni) — **rdzeń algorytmiczny v2**

Największy i najtrudniejszy checkpoint. Odpowiednik CP2 w v1, ale trudniejszy: w dwóch
językach, na istniejącym formacie przewodowym i z konsekwencjami dla GC.

### 5.1 Dlaczego nie da się tego zrobić „per znak"

Pułapka, którą trzeba nazwać w ADR-0010, bo jest to najczęstsze błędne podejście:
**formatowanie jako atrybut znaku nie działa.** Jeśli Ania pogrubia zakres, a Bartek
jednocześnie wstawia tekst w jego środek, wstawiony tekst musi być pogrubiony — a przy
atrybutach per znak nie ma go kto pogrubić, bo w chwili wstawienia operacja Ani jeszcze
nie dotarła.

Nie działa też przechowywanie zakresów jako offsetów. Powód jest identyczny jak przy kursorach
w [ADR-0006](adr/0006-warstwa-edytora.md): offset przestaje wskazywać to samo miejsce po cudzej
edycji. Projekt rozwiązał to raz — kotwiczeniem kursora w **identyfikatorze znaku CRDT** — i to
samo rozwiązanie skaluje się na formatowanie.

### 5.2 Projekt (Peritext) — do zweryfikowania w CP13

```
Anchor = (Id charId, Side side)            Side ∈ {BEFORE, AFTER}
Mark   = (Id opId, String type, String value, Anchor start, Anchor end)

Op.AddMark(Id id, String type, String value, Anchor start, Anchor end)
Op.RemoveMark(Id id, String type, Anchor start, Anchor end)
```

- **`Side` jest istotne, nie kosmetyczne.** Znacznik kończący się „za znakiem X" i zaczynający
  „przed znakiem Y" zachowują się różnie, gdy ktoś wstawi tekst między X a Y — jeden go obejmie,
  drugi nie. To jest mechanizm, dzięki któremu pisanie na końcu pogrubionego fragmentu zostaje
  pogrubione, a pisanie na końcu linku już do niego nie należy.
- **`RemoveMark` musi być operacją zakresową**, nie referencją do identyfikatora `AddMark`.
  Dwoje ludzi może pogrubić zachodzące na siebie zakresy, a trzeci odgrubić część wspólną.
- **Rozstrzyganie konfliktów:** dla danego znaku i danego typu znacznika wygrywa operacja
  o najwyższym `(lamport, replicaId)`, która go obejmuje. Ten sam porządek totalny, którego
  używa już RGA — bez nowego mechanizmu arbitrażu.
- **Renderowanie:** zamiatanie po liście bloków z utrzymywaniem zbioru aktywnych znaczników,
  emisja spanów. `O(n + m log m)`.
- **Zachowanie rozszerzania** (`expand`) jest własnością **typu** znacznika, nie pojedynczej
  operacji: pogrubienie rozszerza się na końcu, link nie. Tablica typów w jednym miejscu.

### 5.3 Dwie pułapki, których kompilator nie złapie

`Op` jest `sealed interface Op permits Op.Insert, Op.Delete`. Dodanie wariantów sprawi, że
kompilator wskaże wszystkie wyczerpujące `switch`-e — `RgaDocument.isAlreadyKnown`,
`RgaDocument.tryApply`, `OpCodec.toJson`. To jest zaleta i warto ją w ADR nazwać.

Ale **dwa miejsca używają trójargumentowego `instanceof` zamiast `switch`-a** i przejdą
kompilację po cichu, z błędnym wynikiem:

| Miejsce | Kod | Skutek |
|---|---|---|
| `OperationStore.java:83` | `op instanceof Op.Insert ? "i" : "d"` | Nowy typ operacji zapisany w logu jako **usunięcie**. Korupcja źródła prawdy |
| `DocumentStompController.java:75` | `op instanceof Op.Insert ? "insert" : "delete"` | Błędny atrybut w trace — mylące dane diagnostyczne |

Oba do przepisania na `switch` **przed** dodaniem wariantów, jako osobny commit.

### 5.4 Format przewodowy i zapis

- `OpCodec`: nowe dyskryminatory `"t"` (obok `"i"` i `"d"`). Format jest ręcznie pisany
  i udokumentowany w javadocu — rozszerzenie jest czyste, javadoc do aktualizacji.
- `operations.op_type CHAR(1)`: **bez migracji schematu**, wystarczą nowe kody. `payload`
  jest `JSONB`. Schemat z V1 okazuje się tu wystarczający — warto to odnotować.
- `DocumentSnapshot.CURRENT_FORMAT_VERSION`: podbicie. Migracja **darmowa z projektu** —
  `V1__initial_schema.sql:46` mówi wprost, że snapshot o niezgodnej wersji jest ignorowany,
  a stan odtwarzany z logu. Decyzja z ADR-0003 spłaca się tutaj.
- **Korpus konformancyjny do regeneracji** + nowe wektory dla znaczników. Job CI
  „wektory konformancyjne są aktualne" pilnuje reszty.

### 5.5 Konsekwencja dla GC — najpoważniejsza w całym planie

[ADR-0005](adr/0005-tombstone-gc.md) zabrania usuwania tombstone'u, który jest `originLeft`
innego bloku. README już przyznaje, że przez to GC odzyskuje praktycznie tylko ogon dokumentu.

**Po CP13 dochodzi drugi warunek: tombstone będący kotwicą znacznika też nie może zniknąć.**
Po CP14 — trzeci: kotwica komentarza. Skuteczność GC spada dalej, a rich text zwiększa
liczbę kotwic proporcjonalnie do ilości formatowania.

To może wymusić przeprojektowanie, które README identyfikuje jako właściwe rozwiązanie:
**porzucanie treści skasowanych węzłów przy zachowaniu ich w strukturze** (podejście Yjs).
Zmienia reprezentację `Block` i unieważnia format snapshotu — ale format snapshotu i tak
jest w tym checkpoincie podbijany, więc **jeśli ta zmiana ma nastąpić, to teraz jest najtańszy
moment.**

Decyzja do podjęcia na starcie CP13, nie w trakcie. Zapisać w ADR-0011 z rewizją ADR-0005.

### 5.6 Frontend

`remoteCursors.ts` jest gotowym wzorcem: identyfikatory CRDT → offsety → dekoracje CodeMirrora,
przeliczane na ostatnią chwilę przed rysowaniem. Znaczniki idą tą samą drogą przez
`Decoration.mark()`. CodeMirror pozostaje wyłącznie warstwą widoku — [ADR-0006](adr/0006-warstwa-edytora.md)
nie wymaga rewizji.

Pasek narzędzi, skróty klawiszowe, `bridge.ts` rozszerzony o transakcje formatowania.

**Podwójna implementacja (Java + TS) obowiązuje.** To jest dług opisany w README i CP13 go
podwaja — ale rezygnacja z niej po stronie klienta oznaczałaby, że przeglądarka nie potrafi
renderować formatowania bez odpowiedzi serwera, czyli utratę optymistycznej edycji.

### 5.7 Testy

| Test | Jaki failure mode wyłapuje |
|---|---|
| `markConvergence` (property) | Znaczniki nie są przemienne — dwie osoby formatują, każda widzi co innego |
| `insertIntoMarkedRangeInheritsMark` | Klasyczny błąd „formatowanie per znak" z 5.1 |
| `concurrentBoldAndUnboldConverges` | Brak porządku totalnego na znacznikach; miganie formatowania |
| `markSurvivesConcurrentDeleteOfBoundary` | Kotwica na skasowanym znaku rozspójnia strukturę |
| `expandBehaviourDiffersByMarkType` | Pisanie za linkiem rozszerza link |
| `markAnchorPreventsGarbageCollection` | GC kasuje kotwicę — objaw dopiero przy następnej edycji w to miejsce |
| konformancja Java ↔ TS dla znaczników | Ciche rozjechanie się obu implementacji |

---

## 6. CP14 — Komentarze (2–2,5 dnia)

### 6.1 Dwie warstwy, z których tylko jedna jest CRDT

**Kotwica** — zakres w dokumencie. To jest znacznik typu `comment` z identyfikatorem wątku
jako wartością. Cała maszyneria powstała w CP13; ten checkpoint jej **używa**, nie buduje.

**Treść wątku** — komentarze i odpowiedzi. **Świadomie nie jest CRDT.** Wiadomość ma jednego
autora, jest dopisywana w całości i nie jest współbieżnie edytowana znak po znaku. Zwykłe
tabele relacyjne wystarczą.

To rozróżnienie jest warte zapisania w ADR-0012, bo pokusa „skoro mamy CRDT, wszystko będzie
CRDT" jest realna, a odpowiedzią jest: CRDT rozwiązuje konkretny problem (współbieżna edycja
tej samej sekwencji) i stosowanie go tam, gdzie tego problemu nie ma, to koszt bez korzyści.

Migracja `V4__comments.sql`: `comment_threads` (doc_id, status, created_by, resolved_at),
`comment_messages` (thread_id, author_id, body, created_at, edited_at).

### 6.2 Komentarz osierocony

Co się dzieje, gdy zakotwiczony tekst zostanie skasowany. Kotwica przeżywa, bo tombstone'y
przeżywają — wątek staje się zerowej długości.

Decyzja produktowa: pokazać jako „odłączony" na marginesie, nie kasować po cichu. Kasowanie
byłoby utratą rozmowy w reakcji na edycję tekstu, czyli zachowaniem zaskakującym.
Wchodzi do sekcji failure modes w README.

### 6.3 Uprawnienia

Rola `commenter` z CP12 nabiera tu treści: zapis do wątków, brak zapisu do tekstu.
Egzekwowane mapowaniem „rola → typy operacji" z 4.3 — jeśli CP12 zrobiony poprawnie,
ten checkpoint nie dodaje nowego kodu autoryzacji, tylko wpis w tablicy.

### 6.4 Testy

| Test | Jaki failure mode wyłapuje |
|---|---|
| `commentAnchorFollowsConcurrentEdit` | Kotwica na offsetach zamiast identyfikatorach — komentarz wskazuje cudzy tekst |
| `deletingAnchoredTextDetachesThread` | Kasowanie tekstu gubi rozmowę |
| `commenterCannotEditDocumentText` | Regresja mapowania ról z CP12 |
| `threadSurvivesDocumentRestore` | Przywrócenie wersji z historii gubi komentarze dodane później |

---

## 7. CP15 — Ponowny pomiar i dokumentacja (1,5–2 dni)

**Obowiązkowy, nie opcjonalny.** Po CP13 wszystkie liczby w README opisują inny system.
Zostawienie ich byłoby dokładnie tym, przed czym `CLAUDE.md` ostrzega — dokumentacją
odtwarzaną z pamięci zamiast mierzoną.

- Harness przebiega ponownie, **z ruchem presence i z operacjami formatowania** w profilu.
- Nowy wymiar w tabeli: udział znaczników w ruchu i w rozmiarze snapshotu.
- Zmierzony narzut GC po dodaniu kotwic — liczba potwierdzająca lub obalająca obawę z 5.5.
- README: rozdzielenie „zrobione w v2" od „nadal poza zakresem"; nowe failure modes
  (komentarz osierocony, odebranie uprawnień w trakcie sesji, dokument usunięty miękko).
- Nowe demo — GIF ze skryptu Playwrighta, jak w CP9. Cztery nowe funkcje wymagają
  nowego scenariusza.
- **Powrót do ADR-ów** (0008–0012) — konwencja domu: dopisać, co faktycznie wyszło,
  łącznie z tym, co okazało się gorsze niż zakładano.

---

## 8. Estymata

| Checkpoint | Dni |
|---|---:|
| CP10 — Fundament | 2–3 |
| CP11 — Multi-dokument | 2–3 |
| CP12 — Uprawnienia | 2,5–3,5 |
| CP13 — Rich text | 5–7 |
| CP14 — Komentarze | 2–2,5 |
| CP15 — Pomiar + dokumentacja | 1,5–2 |
| **Razem** | **15,5–21 dni roboczych** |

Dla porównania: v1 (CP1–CP9) zajęła ~13–17 dni. **Ten plan to drugi projekt tej samej
wielkości co pierwszy.** Warto to wiedzieć przed startem, a nie w połowie CP13.

Jeśli budżet jest mniejszy niż całość, sensowne punkty zatrzymania to koniec CP12
(produkt z wieloma dokumentami i uprawnieniami, bez rich textu — spójna całość)
albo koniec CP13 (bez komentarzy). **Zatrzymanie w środku CP13 nie jest punktem
zatrzymania** — format przewodowy i korpus konformancyjny byłyby w stanie przejściowym.

---

## 9. Ryzyka

| Ryzyko | Prawdopodobieństwo | Przeciwdziałanie |
|---|---|---|
| **Rozcieńczenie przekazu** — projekt traci jedno zdanie opisu | Wysokie | README nadal otwiera się rdzeniem CRDT i demem, nie listą funkcji. Rich text prezentowany jako *konsekwencja* dobrego rdzenia, nie jako osobna funkcja |
| **CP13 przeciąga się** — Peritext w dwóch językach to najtrudniejsza rzecz w całym projekcie | Wysokie | Zamknąć na jednym typie znacznika (pogrubienie) z pełnymi property testami, dopiero potem rozszerzać. Typy są tanie, mechanizm jest drogi |
| **GC przestaje działać** po dodaniu kotwic | Średnie | Decyzja o przeprojektowaniu reprezentacji bloku **na starcie CP13**, nie w trakcie (5.5) |
| Podatność IDOR wchodzi razem z CP11, zanim CP12 ją zamknie | Średnie | CP11 nie wchodzi na hostowaną instancję przed CP12. Albo: tymczasowe ograniczenie do dokumentów własnych |
| Liczby w README dezaktualizują się cicho | Średnie | CP15 obowiązkowy; do rozważenia job CI porównujący harness z zapisanym wynikiem |
| Dwie implementacje rozjeżdżają się na znacznikach | Niskie | Korpus konformancyjny + istniejący job CI. Mechanizm sprawdzony w v1 |

---

## 10. Czego ten plan świadomie NIE obejmuje

Zakres rozszerzony nie znaczy nieograniczony. Poza zakresem zostają, do sekcji
„co zrobiłbym inaczej w produkcji":

- **Osadzone obiekty** (tabele, obrazy, bloki kodu). Peritext obsługuje znaczniki nad tekstem;
  osadzone obiekty to inny problem strukturalny.
- **Undo świadome CRDT.** Nadal brak, nadal wymienione w README. Po rich texcie robi się
  trudniejsze — cofnięcie musi objąć znaczniki.
- **Fugue zamiast RGA.** Anomalia przeplotu zostaje udokumentowana, nie naprawiona.
- **Presence w klastrze.** Wymienione w README; nie zmienia się przez ten plan.
- **Edycja offline komentarzy.** Bufor offline obejmuje operacje CRDT; komentarze wymagają
  połączenia.
- **Powiadomienia, wzmianki, eksport do docx/pdf, wersje mobilne.** Zakres produktowy
  bez zawartości inżynierskiej odpowiadającej tezie projektu.
