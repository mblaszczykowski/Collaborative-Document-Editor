# ADR-0003 — Historia zmian: event log jako źródło prawdy, snapshoty jako cache

- **Status:** Zaakceptowany
- **Data:** 2026-08-14
- **Dotyczy checkpointów:** CP3 (schemat), CP5 (time travel), CP8 (koszt odtworzenia)

---

## Kontekst

Wymagania z `idea.md`:
- cofnięcie dokumentu do **dowolnego punktu w czasie**,
- **audit trail** — kto co zmienił,
- odtworzenie stanu po restarcie instancji.

Dodatkowo z ADR-0001: operacje CRDT muszą przetrwać, bo klient offline może wrócić
z operacją, której origin jest gdzieś w przeszłości — potrzebujemy pełnego kontekstu
przyczynowego, nie tylko aktualnego tekstu.

Napięcie do rozstrzygnięcia: **koszt zapisu i miejsca** kontra **koszt odtworzenia stanu**.

---

## Rozważane opcje

### Opcja A — Tylko aktualny stan (kolumna z tekstem, `UPDATE` przy każdej zmianie)

**Za:** trywialny odczyt, minimalne miejsce.
**Przeciw:** żadnej historii, żadnego audit trail, brak możliwości domknięcia struktury CRDT
(tombstone'y i identyfikatory znikają), a klient wracający z offline nie ma się do czego
odnieść. Wyklucza dwa z trzech wymagań. Odrzucone bez dalszej analizy.

### Opcja B — Tylko event log (czyste event sourcing)

Append-only tabela operacji. Stan = redukcja wszystkich operacji od początku.

**Za:**
- Pełna historia i audit trail za darmo — log *jest* historią.
- Idealnie pasuje do CRDT: operacja jest już naturalną, niemutowalną jednostką zmiany.
- Zapis to `INSERT`, bez kontencji na wierszu — dobrze się skaluje przy wielu piszących.
- Idempotencja zapisu wynika z klucza głównego `(doc_id, replica_id, counter)`, bo taki
  klucz jest naturalny dla identyfikatora operacji CRDT. Retransmisja po reconnect
  nie może zdublować wiersza.

**Przeciw:**
- **Czas odtworzenia rośnie liniowo z historią.** Dokument z milionem operacji wymaga
  przeczytania i zastosowania miliona operacji przy każdym otwarciu i przy każdym starcie
  instancji. Nie do zaakceptowania.

### Opcja C — Tylko okresowe snapshoty

Co N sekund zapisujemy zserializowany stan CRDT.

**Za:** stały czas odczytu.
**Przeciw:** historia ma ziarnistość snapshotu — „cofnij do dowolnego punktu" degraduje się do
„cofnij do co którejś minuty". Audit trail per zmiana znika. Operacje między snapshotami
są bezpowrotnie tracone, więc klient offline nie ma się do czego doczepić.

### Opcja D — Event log jako źródło prawdy + snapshoty jako cache

Log jest jedyną prawdą; snapshot to zmaterializowany wynik redukcji logu do konkretnego
punktu, który wolno w każdej chwili skasować i odtworzyć.

**Za:** łączy pełną historię z ograniczonym czasem odczytu.
**Przeciw:** dwa mechanizmy do utrzymania i spójności; snapshot może być nieaktualny
lub uszkodzony, więc musi być weryfikowalny.

---

## Decyzja

**Opcja D.**

```
operations   (append-only, ŹRÓDŁO PRAWDY)
  PK (doc_id, replica_id, counter)   ← idempotencja zapisu za darmo
  op_type, payload, origin_left, server_seq, user_id, created_at

snapshots    (CACHE — wolno skasować w każdej chwili)
  PK (doc_id, up_to_server_seq)
  state (bytea), text_length, tombstone_count, created_at
```

### Zasady

1. **`server_seq`** — monotoniczny licznik per dokument, nadawany przy zapisie. Nie ma
   znaczenia dla poprawności CRDT (zbieżność nie zależy od kolejności — ADR-0001), służy
   wyłącznie jako **kursor**: „dosyłam ci wszystko powyżej seq X" przy reconnect i przy
   anti-entropy między instancjami (ADR-0004). Rozdzielenie porządku *przyczynowego*
   (version vector) od porządku *zapisu* (server_seq) jest tu celowe — mylenie ich to
   klasyczne źródło błędów.

2. **Odtworzenie stanu** = najbliższy snapshot o `up_to_server_seq ≤ T` + replay operacji
   z przedziału `(up_to_server_seq, T]`.

3. **Snapshot jest usuwalny.** Jeśli zniknie albo nie da się go zdeserializować (np. po zmianie
   formatu), system odtwarza z logu od zera — wolniej, ale poprawnie. Snapshot nigdy nie jest
   jedynym nośnikiem informacji. Test w CP5: skasowanie wszystkich snapshotów nie zmienia
   żadnego wyniku, tylko czas odpowiedzi.

4. **Próg snapshotowania: co 500 operacji lub 30 s bezczynności dokumentu, cokolwiek pierwsze.**
   500 wynika z rzędu wielkości — replay 500 operacji RGA to jednostki milisekund, więc
   ogon nieskompresowanego logu nigdy nie dominuje czasu otwarcia. Snapshot po bezczynności
   łapie dokumenty, które nigdy nie osiągną 500 operacji, ale są często otwierane.
   **Wartości do skalibrowania w CP8** — zmierzę koszt replaya i koszt zapisu snapshotu
   i dobiorę próg do faktycznych liczb, zamiast zostawiać zgadniętą stałą.

5. **Batchowanie zapisu operacji: grupowy `INSERT` co 50 ms albo co 100 operacji.**
   Kompromis trwałość/przepustowość jest tu jawny: w oknie do 50 ms operacja jest już
   rozesłana do innych klientów, ale jeszcze nie w bazie. Pad instancji w tym oknie oznacza
   utratę operacji, którą inni klienci już widzą → rozjazd. **To jest realny failure mode
   i trafia do sekcji Failure modes w README**, wraz z mitygacją: klient trzyma operację
   w buforze do momentu otrzymania `ack` z `server_seq` (czyli do potwierdzenia zapisu),
   i retransmituje przy reconnect. Ack po zapisie, nie po broadcaście — to nie jest detal.

6. **Przywrócenie starej wersji zapisujemy jako NOWE operacje**, nigdy jako obcięcie logu.
   Powody: log pozostaje append-only (audytowalność), inne podłączone klienty widzą restore
   jako zwykłą edycję i zbiegają się normalnie, a „kto przywrócił wersję z wtorku" jest
   samo w sobie wpisem w audit trailu. Obcięcie logu psułoby wszystkie trzy własności naraz.

---

## Konsekwencje

### Pozytywne
- Time travel do dowolnej operacji, nie do najbliższego snapshotu.
- Audit trail jest produktem ubocznym, nie osobnym mechanizmem.
- Idempotencja zapisu wynika z klucza głównego — bez kodu deduplikującego.
- Snapshot można w każdej chwili unieważnić przy zmianie formatu serializacji CRDT,
  bez migracji danych.

### Negatywne
- **Miejsce rośnie monotonicznie.** Dokument aktywnie edytowany przez rok to log, którego
  nikt nie kasuje. W produkcji potrzebne byłoby archiwizowanie starych segmentów do
  obiektowego storage'u i kompakcja — poza zakresem, trafia do „co inaczej w produkcji".
- **Dwa mechanizmy do przetestowania.** Musi istnieć test „skasuj snapshoty → wyniki
  identyczne", inaczej cache po cichu stanie się źródłem prawdy.
- **Okno 50 ms niezapisanych operacji** — opisane wyżej, świadomy kompromis.
- **Serializacja snapshotu wiąże się z wewnętrzną strukturą CRDT.** Każda zmiana w `crdt-core`
  potencjalnie unieważnia snapshoty. Mitygacja: numer wersji formatu w snapshocie, przy
  niezgodności snapshot jest ignorowany (a nie: próbujemy go migrować).
- Zapytanie „stan na czas T" nie jest darmowe — kosztuje odczyt snapshotu + replay ogona.
  Przy przeglądaniu historii krok po kroku to N razy ten koszt. **Do zmierzenia w CP5/CP8**;
  jeśli będzie bolało, mitygacją jest replay przyrostowy przy przewijaniu timeline'u
  (trzymamy stan i dokładamy operacje) zamiast odtwarzania od snapshotu za każdym razem.

### Zweryfikowane w CP8

- **Serializacja snapshotu miała błąd, który ujawnił się dopiero przy dużym dokumencie.**
  `DataOutputStream.writeUTF` koduje długość na dwóch bajtach i wywala się powyżej 64 KB —
  a blok run-length po scaleniu ciągłego pisania spokojnie ten rozmiar przekracza. Skutkiem
  było **ciche zaprzestanie zapisywania snapshotów** dla dokumentów o 100 tys. znaków;
  jedynym objawem był wpis w logu, a system działał dalej, tylko wolniej się otwierał.
  To jest dokładnie ten rodzaj awarii, przed którym broni traktowanie snapshotu jako cache'u:
  nic nie zginęło. Naprawione, test regresyjny w `SnapshotCodecTest`.
- **Równoważność „z snapshotami" i „bez snapshotów" jest testowana wprost** i przechodzi —
  zarówno dla bieżącego stanu, jak i dla podróży w czasie. Bez tego testu powyższy błąd
  mógłby przejść niezauważony jako „coś jest wolniejsze".
- **Progu 500 operacji ani rozmiaru logu nie skalibrowałem pomiarem.** Wyniki wydajnościowe
  pokazały, że wąskim gardłem jest dostawa do klientów, a nie odczyt z bazy, więc kalibracja
  tego progu byłaby optymalizacją miejsca, które nie boli. Zostaje wartość dobrana
  rozumowaniem, oznaczona jako niezweryfikowana.

### Zweryfikowane w CP10

- **Punkt 4 tej decyzji opisywał mechanizm, którego nie było w kodzie.** „Snapshot po
  bezczynności łapie dokumenty, które nigdy nie osiągną 500 operacji" brzmiało jak opis
  zachowania systemu, a było opisem zamiaru: jedyną zaimplementowaną ścieżką był próg liczby
  operacji, sprawdzany przy zapisie wsadu. Dokument bez nowych operacji nie wchodzi do tej
  ścieżki w ogóle, więc próg bezczynności **nie mógł zadziałać z definicji**, niezależnie
  od wartości w konfiguracji.
- Skutkiem było to, że dokument edytowany krótkimi sesjami po kilka zmian nigdy nie dostawał
  snapshotu, a ogon logu do odtworzenia rósł z każdym otwarciem. Poprawność cała — psuł się
  wyłącznie czas ładowania, i to stopniowo, więc bez metryki nie ma tego jak zauważyć.
- Zaimplementowane w CP10 wraz z testem, który używa **pięciu** operacji przy progu
  dwudziestu, żeby ścieżka „co N operacji" nie mogła zaliczyć go przypadkiem.
- Lekcja ogólniejsza niż sam błąd: ADR opisujący decyzję i ADR opisujący stan systemu czyta
  się tak samo, a znaczą co innego. Wartość konfiguracyjna z uzasadnieniem w komentarzu
  wygląda na działającą — i nikt jej nie sprawdza właśnie dlatego, że wygląda.
