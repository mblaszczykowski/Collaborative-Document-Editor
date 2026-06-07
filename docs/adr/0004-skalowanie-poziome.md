# ADR-0004 — Skalowanie poziome: instancja backendu jako replika CRDT

- **Status:** Zaakceptowany
- **Data:** 2026-08-14
- **Dotyczy checkpointów:** CP3 (implementacja), CP8 (test padu instancji)

---

## Kontekst

Klienci edytujący ten sam dokument mogą trafić na różne instancje backendu (load balancer
rozdziela połączenia WebSocket). Operacja przyjęta przez instancję A musi dotrzeć do klientów
podłączonych do instancji B.

To jest moment, w którym wybór z ADR-0001 zaczyna się opłacać albo mścić — więc traktuję
tę decyzję jako test tamtej.

---

## Rozważane opcje

### Opcja A — Sticky sessions: wszyscy edytujący dokument lądują na tej samej instancji

Load balancer routuje po `doc_id` (consistent hashing).

**Za:** stan dokumentu istnieje w jednym miejscu, zero koordynacji, brak duplikacji pamięci.
**Przeciw:**
- Wymaga LB świadomego domeny (routing po treści, nie po połączeniu) — nietrywialne przy
  WebSocketach, gdzie `doc_id` znany jest dopiero po `CONNECT`+`SUBSCRIBE`.
- Rozkład obciążenia jest tak dobry, jak rozkład popularności dokumentów. Jeden dokument
  z 200 userami zawsze obciąża jedną instancję — skalowanie poziome nie pomaga tam,
  gdzie jest naprawdę potrzebne.
- Rekonfiguracja pierścienia przy dodaniu/usunięciu instancji przerzuca dokumenty
  między węzłami, każdorazowo z przerwą.

### Opcja B — Document affinity z rozproszonym lockiem (Redis / etcd)

Jedna instancja jest „właścicielem" dokumentu; pozostałe proxują do niej operacje.

**Za:** pojedyncze źródło prawdy w pamięci, prosty model rozumowania.
**Przeciw:**
- Wprowadza **rozproszony lock i wybór lidera** do systemu, którego cały sens polega na tym,
  że lidera nie potrzebuje. Zaprzeczenie ADR-0001.
- Failover właściciela to najtrudniejszy przypadek: split-brain, dzierżawy, fencing tokens.
  Dużo skomplikowanego kodu, dużo miejsc na błąd.
- Dodatkowy hop dla każdej operacji przechodzącej przez nie-właściciela.

### Opcja C — Każda instancja jest repliką CRDT, Redis pub/sub jako fan-out

Instancja przyjmuje operację od swojego klienta, scala ją do własnej repliki, zapisuje
do `operations` (ADR-0003), publikuje na kanale Redis `doc:{id}:ops`. Pozostałe instancje
odbierają, scalają do swoich replik i rozsyłają swoim klientom.

**Za:**
- **To jest dokładnie ten model, do którego CRDT został zaprojektowany.** Repliki są
  równorzędne, kolejność dostarczenia nie ma znaczenia, duplikaty są nieszkodliwe
  (idempotencja z ADR-0001). Zero locków, zero lidera, zero sticky sessions.
- Dowolna instancja obsługuje dowolnego klienta → LB może być całkowicie głupi (round-robin).
- Dodanie instancji nie przerzuca żadnego stanu.

**Przeciw:**
- **Duplikacja pamięci:** dokument otwarty przez klientów na 3 instancjach żyje w 3 kopiach.
- **Redis pub/sub to at-most-once.** Restart Redisa, przepełnienie bufora klienta, chwilowa
  partycja — wiadomość ginie bez śladu i bez błędu. Replika, która przegapiła operację,
  rozjeżdża się z resztą i **nigdy sama tego nie naprawi**, bo CRDT gwarantuje zbieżność
  tylko przy założeniu, że wszystkie operacje w końcu dotrą.

---

## Decyzja

**Opcja C — każda instancja jest repliką CRDT** — z jawnym rozwiązaniem problemu gubionych
wiadomości.

### Redis to ścieżka szybka. Postgres to ścieżka autorytatywna.

```
klient ──op──► instancja A
                 │
                 ├─ 1. merge do lokalnej repliki   (natychmiast)
                 ├─ 2. broadcast do własnych klientów (wsadowo, co 10 ms)
                 ├─ 3. INSERT do operations         (ŹRÓDŁO PRAWDY, ADR-0003)
                 └─ 4. PUBLISH doc:{id}:ops         (best-effort, może zginąć)
                             │
                    instancja B ─ SUBSCRIBE ─► merge + broadcast
```

> **Zmiana w kroku 2, wprowadzona w CP10.** Pierwotnie było tu „natychmiast", jedna ramka
> na operację. Jest to zachowanie, które **z definicji nie skaluje się z liczbą klientów**:
> operacja wychodzi w tylu ramkach, ilu jest odbiorców, więc łączna liczba ramek rośnie
> z kwadratem liczby klientów. Przy 50 klientach × 40 operacjach to 100 tys. ramek.
>
> To jest argument z arytmetyki, a nie z pomiaru — i warto go odróżnić od hipotezy o tym,
> **co konkretnie** psuło się w scenariuszu opisanym w README jako nierozstrzygnięty
> („część klientów trwale w tyle"). Przypuszczenie było takie, że kolejka wysyłkowa
> przekracza `send-buffer-size-bytes` i Spring zrywa sesję kodem 4500. **Przypuszczenie
> to jest sprzeczne z ustaleniem z CP8** zapisanym w ADR-0002: statystyki brokera nie
> wykazały wtedy ani jednego zamknięcia z powodu przepełnienia bufora. Dlatego CP10 dokłada
> najpierw metrykę `ws_sessions_closed_total{reason}`, która mierzy to wprost, zamiast przez
> zbiorcze statystyki brokera — a wnioski są dopisane niżej, po pomiarze.
>
> Niezależnie od tego, co okaże się przyczyną rozjazdu, sklejanie ramek jest uzasadnione samo
> w sobie: kwadratowy wzrost liczby ramek jest własnością konstrukcji, a nie objawem błędu.
>
> Okno 10 ms jest o rząd wielkości poniżej progu zauważalności w pisaniu i o rząd wielkości
> poniżej zmierzonych latencji propagacji, więc kompromis nie jest bliski. Musi być
> **wyraźnie krótsze niż `op-batch-window-ms`** — potwierdzenie zapisu, które wyprzedzi
> rozgłoszenie, klient interpretuje jako lukę i prosi o pełną resynchronizację. Dla pewności
> oba bufory są opróżniane jednym zajęciem blokady, z rozgłoszeniem przed zapisem
> (`DocumentReplica.drainForFlush`), więc nie polegamy wyłącznie na relacji między okresami
> dwóch schedulerów.

**Anti-entropy (read-repair) — mechanizm, który zamienia zawodny broadcast w bezpieczny:**

Każda operacja niesie `server_seq` (ADR-0003). Instancja B śledzi najwyższy `server_seq`
widziany dla danego dokumentu. Jeśli przychodzi operacja z `server_seq` wyższym niż
`ostatni + 1`, to znaczy, że w środku jest luka → B dociąga brakujący zakres bezpośrednio
z Postgresa (`SELECT ... WHERE doc_id = ? AND server_seq BETWEEN ? AND ?`) i scala.

Dodatkowo **okresowy sweep co 30 s**: dla każdego dokumentu trzymanego w pamięci instancja
porównuje swój najwyższy `server_seq` z `MAX(server_seq)` w bazie i dociąga różnicę.
To łapie przypadek, którego detekcja luk nie łapie: **utratę operacji na samym końcu**,
po której nie przyszła już żadna kolejna (np. Redis pada i dokument cichnie). Bez sweepu
taka replika zostaje rozjechana aż do następnej edycji.

Częstotliwość 30 s jest kompromisem: to górna granica czasu życia niewykrytego rozjazdu
w najgorszym scenariuszu, przy koszcie jednego lekkiego zapytania na dokument w pamięci.
**Do skalibrowania w CP8** na podstawie zmierzonego kosztu przy realistycznej liczbie
otwartych dokumentów.

Kluczowa własność: **żadna z tych ścieżek naprawczych nie musi być szybka ani uporządkowana**,
bo scalanie jest przemienne i idempotentne. Operacja dociągnięta z bazy 30 sekund po fakcie
daje ten sam wynik, co dostarczona natychmiast. To jest dokładnie ta własność, dla której
wybraliśmy CRDT — i tu przestaje być teoretyczna.

### Ewikcja z pamięci

Replika dokumentu bez podłączonych klientów żyje jeszcze **5 minut**, potem jest usuwana
z pamięci (stan i tak jest w Postgresie). Krótsza wartość powoduje ciągłe przeładowywanie
dokumentów przy chwilowych rozłączeniach; dłuższa niepotrzebnie trzyma pamięć. Do kalibracji
w CP8 wraz z pomiarem zużycia pamięci per otwarty dokument.

---

## Konsekwencje

### Pozytywne
- **Load balancer nie musi nic wiedzieć o domenie.** Round-robin wystarcza.
- **Brak lidera, locka, split-brain i fencingu** — czyli brak całej klasy najtrudniejszych
  błędów systemów rozproszonych.
- **Pad instancji nie gubi danych**: operacje są w Postgresie przed publikacją (kolejność
  3 przed 4), klienci przełączają się na inną instancję i resynchronizują po `server_seq`.
- Zawodność Redisa przestaje być problemem poprawności, a staje się kwestią latencji
  naprawy — mierzalną metryką (`redis_gap_repairs_total`, CP7).

### Negatywne
- **Zużycie pamięci mnoży się przez liczbę instancji trzymających dany dokument.** Przy
  dokumencie 1 MB i 5 instancjach to 5 MB zamiast 1 MB. Akceptowalne przy tej skali,
  ale to prawdziwy koszt wyboru — i pierwsza rzecz, którą bym zmienił, gdyby dokumenty
  były duże lub instancji było dużo. Wtedy właściwą odpowiedzią jest opcja A/B, nie
  optymalizowanie tej.
- **Okno rozjazdu do 30 s** w najgorszym przypadku (zgubiona ostatnia operacja + cisza
  na dokumencie). Klient podłączony do dotkniętej instancji widzi w tym czasie
  nieaktualny tekst. Nie jest to utrata danych, ale jest to widoczna niespójność —
  trafia do sekcji Failure modes w README.
- **Postgres staje się ścieżką krytyczną również dla naprawy**, nie tylko dla trwałości.
  Awaria bazy zatrzymuje zarówno zapis, jak i anti-entropy.
- Sweep to stałe obciążenie proporcjonalne do liczby otwartych dokumentów, niezależne
  od tego, czy coś się dzieje.
- **Detekcja luk zakłada, że `server_seq` jest gęsty** (bez dziur).

  **Zweryfikowane w CP3 — założenie okazało się droższe, niż zakładałem.** Pierwsze podejście
  (`INSERT ... ON CONFLICT DO NOTHING`) jest niepoprawne: numery są rezerwowane dla całego
  wsadu *przed* odrzuceniem duplikatów, więc każdy duplikat zostawia dokładnie taką dziurę,
  jakiej ten mechanizm zabrania. Ostateczne rozwiązanie:
  1. blokada wiersza dokumentu (`SELECT next_server_seq ... FOR UPDATE`) — licznik w kolumnie,
     nie sekwencja Postgresa, bo sekwencja gubi numery przy wycofanej transakcji,
  2. odsiew duplikatów **jednym zapytaniem przed** przydziałem numerów, poprawny mimo
     współbieżności właśnie dlatego, że blokada serializuje piszących do tego dokumentu,
  3. numery przydzielane wyłącznie wierszom, które faktycznie zostaną wstawione.

  **Cena, której nie było w pierwotnej analizie:** zapisy do jednego dokumentu są teraz
  serializowane w całym klastrze. Wsadowanie co 50 ms sprawia, że pod blokadę wchodzi się
  raz na wsad, a nie raz na naciśnięcie klawisza — ale to jest pierwsze miejsce, które
  zacznie ograniczać przepustowość. **Do zmierzenia w CP8.**

  Ciągłość numeracji jest pilnowana testem integracyjnym `serverSequenceHasNoGaps`
  na prawdziwym Postgresie, a nie założeniem.

### Do zweryfikowania w CP8
- Zużycie pamięci na otwarty dokument o zadanym rozmiarze × liczba instancji.
- Ile czasu faktycznie zajmuje naprawa po sztucznym zabiciu Redisa w trakcie edycji
  (test chaosu: kill Redisa, edytuj dalej, zmierz czas do zbieżności wszystkich replik).
- Czy pad instancji w trakcie edycji przechodzi bez utraty operacji — test chaosu
  zabijający instancję między krokiem 3 a 4, i między 2 a 3 (ten drugi *powinien* zgubić
  operację u innych klientów do czasu retransmisji przez klienta — sprawdzam, czy
  faktycznie retransmisja to naprawia).
- Kalibracja interwału sweepu (30 s) i TTL ewikcji (5 min).
