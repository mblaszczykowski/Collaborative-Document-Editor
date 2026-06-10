# ADR-0011 — Komentarze: kotwica w CRDT, treść poza nim

- **Status:** Zaakceptowany
- **Data:** 2026-08-15
- **Dotyczy checkpointów:** CP14

---

## Kontekst

Komentarz w edytorze dokumentów składa się z dwóch rzeczy, które łatwo pomylić:

1. **Zakotwiczenie** — informacja, którego fragmentu tekstu rozmowa dotyczy.
2. **Rozmowa** — wiadomości, ich autorzy, kolejność.

Pytanie brzmi, **które z nich potrzebuje CRDT**. Odpowiedź „obie, skoro CRDT już mamy"
jest kusząca i kosztowna.

---

## Rozważane opcje

### Zakotwiczenie

**Opcja A — offsety w bazie** (`from`, `to` w tabeli wątku).

**Przeciw:** offsety rozjeżdżają się przy każdej cudzej edycji. Jest to ten sam błąd,
który [ADR-0006](0006-warstwa-edytora.md) odrzucił przy kursorach i [ADR-0010](0010-rich-text.md)
przy formatowaniu — **trzeci raz ten sam błąd, w trzecim miejscu**.

**Opcja B — znacznik CRDT typu `comment`** z identyfikatorem wątku jako wartością.

**Za:** to jest dokładnie ta sama maszyneria, którą wprowadził CP13. Kotwice przesuwają się
razem z tekstem, przeżywają edycję sąsiedztwa i scalają się jak każda inna operacja —
bez ani jednej nowej linijki w warstwie CRDT.

### Rozmowa

**Opcja A — wiadomości jako CRDT.**

**Za:** jednolitość. Wszystko jest operacją, wszystko idzie tym samym kanałem.

**Przeciw:** CRDT rozwiązuje **konkretny problem** — współbieżną edycję tej samej sekwencji.
Wiadomość ma jednego autora, powstaje w całości i nie jest edytowana znak po znaku przez
dwie osoby naraz. Zastosowanie CRDT to więc log operacji, scalanie, tombstone'y, GC
i podwójna implementacja dla tekstu, który tego nie potrzebuje.

**Opcja B — zwykłe tabele relacyjne.**

---

## Decyzja

**Kotwica w CRDT (B), rozmowa poza nim (B).**

Wątek i jego kotwica powstają więc **dwiema drogami**: treść przez REST, kotwica przez
WebSocket jako operacja CRDT. Wygląda to na niespójność i nią nie jest — to są dwie rzeczy
o różnych wymaganiach:

| | kotwica | rozmowa |
|---|---|---|
| musi scalać się bez arbitra | tak | nie |
| musi przetrwać cudzą edycję | tak | nie dotyczy |
| wymaga niskiej latencji | tak (podświetlenie) | nie |
| ma jednego autora | nie | tak |

Widać to w zachowaniu: podświetlenie zakotwiczonego fragmentu pojawia się u drugiej osoby
natychmiast, kanałem operacji; treść rozmowy — przy odświeżeniu panelu.

### Wątek odłączony zamiast usuniętego

Gdy zakotwiczony tekst zostanie skasowany, kotwice pozostają tombstone'ami (chronionymi
przez GC — [ADR-0005](0005-tombstone-gc.md)), a zakres przestaje cokolwiek obejmować.
Wątek jest wtedy **odłączony**: pokazywany z zacytowanym fragmentem z chwili założenia
i wyraźnie oznaczony.

Kopia cytowanego tekstu w bazie jest **celowo redundantna** wobec kotwic. Bez niej odłączona
rozmowa nie miałaby jak pokazać, czego dotyczyła — a rozmowa o nieznanym fragmencie jest
niewiele warta.

### Rozwiązanie nie jest usunięciem

Ta sama zasada, co przy miękkim usuwaniu dokumentów w CP11: kontekst decyzji jest zwykle
jedyną rzeczą, dla której ktoś wraca do komentarzy po miesiącach.

---

## Konsekwencje

### Pozytywne
- CP14 nie dołożył **ani jednej linijki** do warstwy CRDT. Cała kotwica to znacznik z CP13,
  co jest najlepszym dowodem, że tamta warstwa została wydzielona właściwie.
- Rola `commenter` z [ADR-0009](0009-model-uprawnien.md) nabrała treści i potwierdziła
  swój projekt: autoryzacja musiała zejść do **typu operacji**, bo komentarze idą tym samym
  kanałem co edycja. Przewidziane w CP12, sprawdzone w CP14.
- Rozmowy da się czytać zwykłym `SELECT`-em, bez odtwarzania stanu z logu operacji.

### Negatywne

- **Dwie drogi zamiast jednej.** Wątek powstaje REST-em, kotwica WebSocketem, i między nimi
  jest okno, w którym wątek istnieje bez kotwicy. Jeśli klient padnie dokładnie wtedy,
  zostanie wątek niezakotwiczony — widoczny jako odłączony od chwili powstania. Nie jest to
  utrata danych, ale jest to stan, którego nie da się odróżnić od „tekst skasowano".
- **Treść komentarzy nie działa offline.** Bufor offline obejmuje operacje CRDT; komentarz
  wymaga połączenia. Jest to konsekwencja decyzji o wyłączeniu rozmowy z CRDT i świadoma cena.
- **Brak powiadomień i wzmianek.** Komentarz, którego nikt nie zauważy, jest wart tyle,
  co jego brak. Poza zakresem, wymieniony w README.
- **Panel odświeża się przez odpytywanie**, a nie przez zdarzenie. Nowa wiadomość w wątku
  pojawia się u innych dopiero przy ponownym otwarciu panelu albo zmianie dokumentu.
  Właściwym rozwiązaniem jest kanał zdarzeń komentarzy — niezrobione.
- **Usunięcie dokumentu kaskaduje na wątki** (`ON DELETE CASCADE`). Przy miękkim usuwaniu
  z CP11 nie ma to znaczenia, bo dokument nigdy nie jest usuwany twardo — ale jest to
  zależność, która przestanie być bezpieczna, jeśli kiedykolwiek powstanie twarde usuwanie.
