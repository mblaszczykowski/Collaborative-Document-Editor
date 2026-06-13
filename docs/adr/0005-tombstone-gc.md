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

### Faktyczny zasięg GC — zweryfikowane w CP6, wynik gorszy od zakładanego

Warunek „węzeł nie jest originem żadnego istniejącego bloku" jest konieczny dla poprawności,
ale okazał się **znacznie bardziej ograniczający, niż zakładałem przy pisaniu tej decyzji**.

W RGA każdy blok pamięta swojego lewego origina. Usunięcie znaku w środku tekstu dzieli blok
na trzy części, a część leżąca za tombstone'em jest do niego zaczepiona. Tombstone jest więc
originem i **nie wolno go usunąć — niezależnie od tego, jak dawno powstał i jak stabilny
przyczynowo jest**.

Praktyczna konsekwencja: kolekcjonowalne są wyłącznie tombstone'y, do których nic nie referuje,
czyli w praktyce **ogon dokumentu** i fragmenty, w których usunięto całe sąsiedztwo. Tekst
kasowany w środku akapitu zostaje w strukturze na zawsze.

**Dlaczego nie „przepiąć" referencji do originu tombstone'u.** Kuszące, i przy stabilności
przyczynowej wygląda na bezpieczne. Nie jest: reguła wstawiania RGA porównuje znaczniki czasu
elementów napotkanych po originie, więc usunięcie węzła ze ścieżki skanowania zmienia miejsce,
w którym wyląduje *przyszłe* wstawienie zaczepione o ten sam origin. Replika po kompakcji
i replika przed kompakcją umieściłyby ten sam nowy znak w różnych miejscach — czyli
rozjechałyby się. Stabilność przyczynowa mówi, że wszyscy w końcu wiedzą; nie mówi,
że wiedzą jednocześnie.

**Co byłoby właściwą odpowiedzią.** To, co robi Yjs: nie usuwać węzła, tylko **porzucić jego
treść**, zostawiając samą długość. Struktura i wszystkie referencje pozostają nienaruszone,
a odzyskana pamięć jest proporcjonalna do rozmiaru skasowanego tekstu — czyli do tego, co
faktycznie boli. Narzut samych węzłów zostaje, ale jest o rząd wielkości mniejszy.

Nie zaimplementowane — wymaga zmiany reprezentacji {@code Block} i unieważnia format
snapshotu oraz korpus wektorów konformancyjnych. Trafia do sekcji „co zrobiłbym inaczej
w produkcji" jako **znany, nazwany brak**, a nie jako przeoczenie.

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

---

## Zweryfikowane w CP13 — i jest to wiadomość zła

Rich text dokłada **trzeci warunek** do listy „kiedy tombstone'u nie wolno usunąć":

1. nie jest przyczynowo stabilny (warunek pierwotny),
2. jest originem istniejącego bloku (dołożony przy implementacji),
3. **jest kotwicą znacznika formatowania** (CP13).

Trzeci warunek wynika wprost z konstrukcji z ADR-0010: znacznik to zakres między kotwicami
zaczepionymi w znakach, a kotwica na skasowanym znaku ma przetrwać — inaczej skasowanie
sąsiedniego tekstu gubiłoby formatowanie. Cena jest taka, że **każdy zakres formatowania
przypina dwa tombstone'y**, i to zwykle w środku tekstu, a nie na ogonie.

GC był już słaby: README od CP6 mówi wprost, że zbieralny jest praktycznie tylko ogon
dokumentu. Teraz jest **słabszy proporcjonalnie do ilości formatowania**. Dokument mocno
sformatowany i mocno edytowany odzyska mniej niż taki sam dokument bez formatowania.

### Czego to NIE zmienia

Kierunek rozwiązania, opisany w README od CP6, pozostaje ten sam i staje się tylko
pilniejszy: **porzucanie TREŚCI skasowanych węzłów przy zachowaniu ich w strukturze**
(tak robi Yjs). Węzeł zachowany jako pusty punkt zaczepienia kosztuje kilkanaście bajtów
zamiast całego tekstu, a wszystkie trzy warunki powyżej pozostają spełnione — bo wszystkie
trzy potrzebują TOŻSAMOŚCI znaku, a nie jego treści.

Plan CP13 wskazywał podniesienie wersji formatu snapshotu jako najtańszy moment na tę zmianę
i **moment ten został przepuszczony**: format podniesiono do wersji 2 bez zmiany reprezentacji
bloku. Powodem był brak pomiaru — nie wiem, ile pamięci realnie zajmują tombstone'y
w dokumencie z formatowaniem, więc nie wiem, czy zmiana reprezentacji jest warta swojego
kosztu. Pomiar należy do CP15; jeśli wypadnie źle, kolejna okazja to następne podniesienie
wersji formatu.

### Zabezpieczenie, które prawie nie zadziałało

Test pilnujący trzeciego warunku w pierwszej wersji **przechodził również z wyłączoną
ochroną kotwic**. Kotwiczył w środku tekstu, a znak w środku jest originem następnego, więc
chronił go już warunek drugi. Wykryte mutacją; test kotwiczy teraz na ostatnim znaku
dokumentu, który nie jest originem niczego.

Jest to trzeci raz w tym projekcie, gdy mutacja ujawnia test niesprawdzający tego, co
deklaruje — i drugi, w którym przyczyną było to, że sprawdzana własność była przypadkowo
zapewniona przez inny, wcześniejszy mechanizm.

---

## Zmierzone w CP15 — jest gorzej, niż mówi sekcja wyżej

Pomiar, na który czekała poprzednia sekcja, jest zrobiony
([`MarkCostMeasurement`](../../crdt-core/src/test/java/dev/collabedit/crdt/MarkCostMeasurement.java)).
Wynik obala sformułowanie „**każdy zakres formatowania przypina dwa tombstone'y**" —
prawdziwe zdanie brzmi: **każdy zakres formatowania przypina cały blok run-length**.

1000 znaków, cały tekst skasowany, 100 znaczników, w pełni stabilny wektor wersji:

| Jak powstał tekst | Bez znaczników | Ze znacznikami |
|---|---:|---:|
| jednym ciągiem (1 blok) | 1000 | **0** |
| znak po znaku (1000 bloków) | 1000 | 800 |

Powód jest w kodzie i był tam od początku: `RgaDocument.gc` iteruje po **blokach**,
a `isAnyCharReferenced` odrzuca blok, w którym choć jeden znak jest kotwicą. Warunek trzeci
nie jest więc warunkiem na znak — jest warunkiem na blok, którego znak jest częścią.
Przy tekście napisanym ciągiem jedna kotwica ratuje cały dokument.

Nie jest to błąd w ochronie kotwic: ochrona musi być zachowawcza, a dzielenie bloku przy
zbieraniu wymagałoby naprawy łańcucha originów — prawa część bloku zaczepia się o ostatni
znak lewej, więc porzucenie prefiksu unieważnia origin tego, co zostaje. Tanie obejście
(przycinanie wyłącznie ogona za ostatnią kotwicą) nie daje nic w przypadku, który boli:
kotwice rozsiane po całej długości.

**Konsekwencja dla decyzji:** kierunek się nie zmienia, ale przestaje być opcjonalny.
Porzucanie treści przy zachowaniu węzła w strukturze rozwiązuje ten problem **w całości**,
bo wtedy blok nie musi być zbierany, żeby oddać pamięć — wystarczy, że straci tekst.
Do zrobienia przy najbliższym podniesieniu wersji formatu snapshotu; tym razem z liczbą
w ręku, a nie z przeczuciem.

**Czego pomiar nie mówi:** ile to znaczy w prawdziwym dokumencie. Bloki w prawdziwej sesji
nie są ani jednym ciągiem tysiąca znaków, ani tysiącem bloków po znaku — powstają z przeplotu
edycji kilku osób i mają rozmiar, którego nie zmierzyłem. Obie liczby w tabeli są skrajnościami
i podaję je jako skrajności, a nie jako prognozę.

---

## CP18 — zmieniona reprezentacja, i liczba z 0% na 100%

Sekcja wyżej kończy się zdaniem „kierunek się nie zmienia, ale przestaje być opcjonalny".
CP18 to zrobił. Ta sekcja zapisuje, co faktycznie wyszło — łącznie z tym, co okazało się tańsze,
niż zakładano, i z tym, co plan zgadł źle.

### Zmiana

Blok usunięty może teraz **porzucić treść, zostając w strukturze**. Zachowuje tożsamość,
długość, origin i pozycję; traci wyłącznie znaki, których i tak nikt nie widzi.

Uzasadnienie było już wypisane wyżej i wystarczyło wziąć je dosłownie: wszystkie trzy powody
trzymania tombstone'u — bycie originem następnego bloku, bycie kotwicą znacznika, stabilność
przyczynowa — potrzebują **tożsamości** znaku, nigdy jego treści. Błąd nigdy nie leżał
w warunkach. Leżał w tym, że „muszę zachować tożsamość" i „muszę zachować wszystko" traktowano
jako to samo wymaganie.

### Zmierzone, to samo stanowisko co w CP15

`MarkCostMeasurement`, bez zmian poza asercjami: 1000 znaków, wszystko skasowane, 100 znaczników
(= 200 kotwic), w pełni stabilny wektor wersji.

| Jak powstał tekst | Przed CP18 | Po CP18 |
|---|---:|---:|
| jednym ciągiem (**1** blok run-length) | **0 %** | **100 %** |
| znak po znaku (1000 bloków) | 80 % | **100 %** |

Pierwszy wiersz jest tym, dla którego to robiono. Drugi jest sprawdzeniem, że nic nie zostało
przy okazji utracone: tam GC potrafił już usuwać całe bloki, więc porzucanie treści musiało
wejść **obok** tego mechanizmu, a nie zamiast niego. Pilnuje tego osobna asercja, bo spadek
w tym wierszu znaczyłby, że tańsza operacja wyparła pełniejszą.

Koszt w snapshocie, ten sam pomiar: **107 B → 108 B** dla dokumentu 50 000 znaków, a narzut
na znacznik bez zmian — 8 B. Jeden bit na blok, zaokrąglony do bajtu.

### Co było tańsze, niż zakładał plan

**Migracja jest darmowa, i to drugi raz, kiedy ADR-0003 się spłaca.** Snapshot w wersji, której
ten kod nie zna, jest *ignorowany*, a nie migrowany — stan odtwarza się z logu operacji, wolniej
i poprawnie. Podniesienie formatu z 2 na 3 nie wymagało więc ani jednej linijki kodu migracyjnego.

**Dług podwójnej implementacji tym razem się nie zmaterializował.** README wymienia „dwie
implementacje jednego algorytmu" jako koszt płacony przy każdej zmianie algorytmu i CP13 zapłacił
go w całości. CP18 nie zapłacił: klient w TypeScripcie nie ma ani GC, ani odczytu snapshotów —
odtwarza log operacji — więc cała zmiana mieści się w warstwie, której klient nie posiada.
Warto to zapisać, bo jest to kontrprzykład do własnego uogólnienia projektu: dług jest
proporcjonalny do tego, **ile algorytmu klient faktycznie implementuje**, a nie do rozmiaru
samego algorytmu.

### Co plan zgadł źle

Plan nazwał to „jedyną zmianą w rdzeniu" i wycenił na 3–5 dni przy założeniu, że prawdziwym
kosztem będą bliźniak w TypeScripcie i agregaty treapa. Treap nie wymagał **niczego**: czytał
już `block.length()`, a nie tekst, więc zamiana tej metody z wyliczanej na pole była dla niego
niewidoczna. Rzeczywisty koszt siedział w jednym miejscu, którego plan nie wymienił — w każdym
tworzeniu `Block` z innego `Block`, czyli na ścieżkach snapshotu i odtwarzania, gdzie
odtworzenie przez publiczny konstruktor rzuca wyjątkiem na bloku bez treści.

### Przepuszczona okazja — rozliczona

Ten ADR zapisał wcześniej, że CP13 przepuścił najtańszy moment na tę zmianę (podniesienie formatu
do wersji 2) z braku pomiaru. Po wykonaniu zmiany koszt da się podać zamiast szacować: **był
mały.** Praca dotyczyła `Block`, `RgaDocument.gc` i `SnapshotCodec`, a istnienie warstwy
znaczników niczego z tego nie utrudniło. Zrobienie tego w CP13 oszczędziłoby jedno podniesienie
wersji formatu i nic poza tym.

Co stawia decyzję z CP13 w lepszym świetle, niż stała tu wcześniej, i warto to powiedzieć wprost:
bez pomiaru z CP15 zmiana zostałaby zrobiona na domysł co do tego, gdzie idzie pamięć — a domysł
zapisany w planie („dwa tombstone'y na znacznik") mylił się o cały poziom struktury.

### Nadal prawdziwe po CP18

- Okno GC (7 dni) to nadal wartość zgadnięta, nie wyliczona z telemetrii.
- Tombstone w środku tekstu nadal jest originem następnego bloku i nadal nie wolno go usunąć.
  CP18 tego nie zmienia — sprawia, że kosztuje ustaloną garść bajtów zamiast tekstu, który
  wcześniej trzymał.
- Nic nie odzyskuje samych obiektów bloków. Dokument edytowany dostatecznie długo nadal zbiera
  wpisy na liście bloków i to jest teraz dominujący pozostały koszt.
