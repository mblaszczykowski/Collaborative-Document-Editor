# Collaborative Document Editor — instrukcje projektowe dla Claude Code

## Cel projektu (przeczytaj to najpierw)

Aplikacja ma solidnie działać — to warunek konieczny, nie opcjonalny. Ale "działa" to za mało, żeby to był dobry projekt portfolio. Budujemy **artefakt, który ma udowodnić senior-level full-stack engineering** rekruterowi/tech leadowi przeglądającemu repo przez 3-5 minut, więc oprócz działającego kodu każda decyzja techniczna ma być:
1. Świadoma i uzasadniona (nie "bo tak się zwykle robi")
2. Udokumentowana w miejscu, gdzie ktoś ją znajdzie
3. Testowalna i zmierzona liczbami, nie przymiotnikami

Jeśli w trakcie pracy pojawi się wybór między "szybciej działające" a "pokazuje głębsze zrozumienie problemu" — wybieraj to drugie i **nie pytaj o zgodę**, nawet gdy wydłuża to pracę znacząco. Koszt zapisz w raporcie końcowym (patrz *Tryb pracy* na dole pliku).

## Język

**Językiem projektu jest angielski.** Decyzja i jej uzasadnienie: [PLAN-v3 §0.2](docs/PLAN-v3.md).

- **Cała nowa treść powstaje po angielsku** — kod, komentarze, javadoc, testy, ADR-y, komunikaty commitów, README.
- Istniejąca treść po polsku jest migrowana w kolejności: README → `docs/adr/` → komentarze w kodzie → `docs/architektura.md` i `docs/crdt-design.md` → plany.
- **Plany (`IMPLEMENTATION_PLAN.md`, `PLAN-v2.md`, `PLAN-v3.md`) zostają po polsku.** To seria historyczna — zapis tego, co myślano w danym momencie. Tłumaczenie takiego zapisu niesie ryzyko, że przy okazji „poprawi się" wnioski, które wtedy brzmiały inaczej.
- Ten plik (`CLAUDE.md`) też zostaje po polsku — to instrukcja dla mnie i dla Claude'a, nie artefakt dla czytelnika repozytorium.
- Tłumacząc, **nie skracaj i nie wygładzaj**. Wartością tych komentarzy jest to, że mówią *dlaczego* i przyznają się do pomyłek. Angielski ma zachować tę samą szczerość i tę samą konkretność, łącznie ze zdaniami typu „to nie zadziałało i oto dlaczego".

## Kontekst techniczny

- Backend: Java 21, Spring Boot, PostgreSQL, WebSocket (STOMP)
- Frontend: React + TypeScript
- Algorytm sync: CRDT (nie OT — patrz ADR-001, do napisania jako pierwszy dokument w projekcie)
- Infrastruktura: Docker Compose, GitHub Actions CI/CD
- Observability: OpenTelemetry + Prometheus/Grafana

## Twarde wymagania — nie pomijaj żadnego

### 1. Zanim napiszesz pierwszą linijkę kodu domenowego
- Stwórz `/docs/adr/` i zapisz ADR dla: wyboru CRDT vs OT, wyboru transportu (WebSocket+STOMP vs SSE vs raw WS), strategii historii zmian (event log vs periodic snapshoty), strategii scalowania horyzontalnego (Redis pub/sub między instancjami)
- Format ADR: kontekst → rozważane opcje → decyzja → konsekwencje (w tym negatywne). Jeśli nie znasz jeszcze konsekwencji, napisz "do zweryfikowania w fazie X" — nie zmyślaj pewności, której nie masz

### 2. Commit history jako narracja
- Conventional Commits (`feat:`, `fix:`, `refactor:`, `test:`, `docs:`)
- Każdy commit to jedna logiczna zmiana, nie "wip" ani "final v3"
- Commituj też decyzje, które się nie udały, jeśli są pouczające (np. "revert: naive last-write-wins merge — patrz ADR-001 dlaczego to nie działa") — historia porażek i iteracji jest bardziej wiarygodna niż linowy sukces

### 3. Testy — priorytetyzuj w tej kolejności
1. Property-based testy algorytmu CRDT (losowe sekwencje operacji w losowej kolejności muszą zawsze konwergować do tego samego stanu) — to jest NAJWAŻNIEJSZY test w całym projekcie, bo dowodzi poprawności core algorytmu
2. Testy integracyjne WebSocket z Testcontainers (prawdziwy Postgres, nie mock)
3. Test chaosu: symulacja losowych opóźnień i rozłączeń, sprawdzenie że system się nie rozjeżdża
4. Dopiero potem standardowe testy jednostkowe kontrolerów/serwisów

Nie pisz testów "dla pokrycia". Każdy test ma odpowiadać na pytanie "jaki realny failure mode to wyłapuje".

### 4. Observability od pierwszego dnia, nie na końcu
- Distributed tracing pojedynczej operacji edycji: klient → WebSocket → merge CRDT → zapis → broadcast do innych klientów
- Metryki: liczba aktywnych sesji, latencja propagacji (p50/p95/p99), liczba konfliktów rozwiązanych/sekundę
- Jeśli zabraknie czasu na coś, obserwowalność NIE jest tym, z czego rezygnujemy — to jeden z głównych sygnałów senioralności

### 5. Benchmarki z prawdziwymi liczbami
- Napisz scenariusz Gatling/k6 symulujący N jednoczesnych userów edytujących ten sam dokument
- Zmierz i zapisz w README: throughput, latencję propagacji w zależności od liczby userów, punkt degradacji wydajności przy rosnącym rozmiarze dokumentu
- Nie akceptuj sformułowań typu "system jest szybki" w dokumentacji — zawsze konkretna liczba z metodologią pomiaru

### 6. Failure modes — dokumentuj explicité
Sekcja w README opisująca dokładnie co się dzieje przy:
- Utracie połączenia klienta w trakcie edycji
- Padzie instancji backendu (failover)
- Jednoczesnej edycji tego samego znaku przez dwóch userów
- Bardzo dużym dokumencie (tombstone garbage collection w CRDT)

### 7. Sekcja "co zrobiłbym inaczej w produkcji"
Świadomie wypisz uproszczenia podjęte dla scope'u portfolio (brak rich text, uproszczony auth, brak shardingu dokumentów) i jak adresowałbyś je przy realnej skali. To pokazuje, że znasz granice własnego rozwiązania — nie pomijaj tej sekcji, nawet jeśli "psuje" wrażenie kompletności.

### 8. Demo, które sprzedaje się samo
Na końcu prac nagraj GIF/wideo (poproś mnie o instrukcję jak to zrobić, jeśli nie masz narzędzia) pokazujące: dwóch userów edytujących ten sam fragment bez konfliktu, reconnect po rozłączeniu, cofnięcie do wcześniejszej wersji. To ma być pierwsza rzecz widoczna w README, przed jakimkolwiek tekstem.

## Czego unikać

- Gotowych bibliotek CRDT (np. Yjs) jako czarnej skrzynki bez własnej implementacji — cel jest pokazać zrozumienie algorytmu, nie umiejętność `npm install`. Można się nimi inspirować/porównać w ADR, ale core logic ma być własny
- Nadmiarowego scope'u **kosztem głębi w core problemie** — lepiej wąsko i dogłębnie niż szeroko
  i płytko. Do zamknięcia CP9 znaczyło to wprost: żadnego rich textu, komentarzy ani multi-dokumentu.
  Od [ADR-0008](docs/adr/0008-rozszerzenie-zakresu.md) zakres jest rozszerzony, ale zasada NIE ulega
  zmianie — każda nowa funkcja ma wnosić własny problem inżynierski (rich text: kotwice przeżywające
  współbieżne wstawienia; uprawnienia: autoryzacja per typ operacji), a nie być pracą dookoła rdzenia.
  Funkcja, która niczego nie dokłada do tezy projektu, nadal jest nadmiarowa.
- Domyślnych configów bez komentarza dlaczego (np. "dlaczego akurat taki interwał heartbeat WebSocket")
- README napisanego dopiero na końcu — pisz go równolegle z kodem, sekcja po sekcji, żeby nie zgadywać post factum co było ważne

## Kolejność pracy (checkpointy)

Checkpointy nie są już wypisane tutaj — mieszkają w planach, bo tam da się przy nich
zapisać, co faktycznie wyszło:

| Plan | Checkpointy | Stan |
|---|---|---|
| [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md) | CP1–CP9 — rdzeń CRDT, transport, historia, klaster, obserwowalność | zamknięty |
| [`docs/PLAN-v2.md`](docs/PLAN-v2.md) | CP10–CP15 — wydajność, multi-dokument, uprawnienia, rich text, komentarze | zamknięty |
| [`docs/PLAN-v3.md`](docs/PLAN-v3.md) | CP16–CP20 — autoryzacja strukturalnie, symulacja deterministyczna, blok bez treści, bramka regresji | **otwarty** |

### Tryb pracy: bez zatrzymywania się na checkpointach

Poprzednia wersja tego pliku kazała zatrzymać się po każdym checkpoincie i zapytać,
czy kontynuować. **Ta zasada jest wycofana** — pracuj przez kolejne checkpointy bez pytania
o zgodę na przejście dalej.

Powód zmiany jest konkretny, a nie kosmetyczny: przy planie v1 i v2 pytanie o zgodę miało
sens, bo każdy checkpoint zmieniał kształt produktu i zła kolejność kosztowałaby dni pracy.
Plan v3 nie dokłada funkcji — domyka rzeczy, których uzasadnienie jest **już zmierzone
i zapisane w repozytorium**. Pytanie „czy kontynuować" przy pozycji, którą ADR-0005 nazywa
wprost nieopcjonalną, jest kosztem bez treści.

Co zastępuje zgodę na przejście — bo zniesienie kontroli nie znaczy zniesienia rozliczenia:

- **Testy i build są bramką.** Checkpoint jest zamknięty, gdy przechodzi to, co go sprawdza,
  a nie gdy skończył się kod. Zielony build bez testu z zębami nie zamyka niczego (§3).
- **Raport zbiorczy na końcu**, nie zgoda po drodze: co zrobione, co zrobione inaczej niż
  zakładał plan i **dlaczego**, co niezrobione. To jest ta sama treść, która wcześniej padała
  przy pytaniu — tylko zebrana, zamiast rozproszona.
- **Zatrzymaj się mimo wszystko**, gdy rzecz jest nieodwracalna albo wychodzi poza plan:
  zmiana schematu bazy niosąca utratę danych, publikacja czegokolwiek na zewnątrz,
  `git push`, rezygnacja z pozycji wpisanej do planu. Autonomia dotyczy **wykonania planu**,
  nie zmiany jego zakresu.
- **Nie udawaj ukończenia.** Jeśli checkpoint utknął, napisz to wprost i przejdź do
  następnego — plan v3 §1 mówi, które da się uciąć bez szkody. Zgłoszenie „zrobione"
  dla czegoś, co nie przechodzi testów, jest gorsze niż niezrobienie.

To samo dotyczy wyboru „szybciej" kontra „głębiej" z sekcji **Cel projektu**: wybieraj głębiej
i **nie pytaj o zgodę na przekroczenie ~30%** — zapisz koszt w raporcie końcowym.
Pytaj tylko wtedy, gdy głębsza droga zmienia zakres planu, a nie czas jego wykonania.
