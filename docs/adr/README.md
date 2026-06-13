# Architecture Decision Records

Zapis decyzji architektonicznych tego projektu. Każdy ADR ma ten sam układ:

**Kontekst** → **Rozważane opcje** → **Decyzja** → **Konsekwencje (w tym negatywne)**

Zasada: sekcja „Konsekwencje negatywne" nigdy nie jest pusta. Jeśli nie widzę wady
w wybranej opcji, to znaczy że nie rozumiem jej wystarczająco — a nie że wady nie ma.

Tam, gdzie konsekwencji nie da się przewidzieć przed implementacją, ADR mówi wprost
**„do zweryfikowania w CP-X"** zamiast zmyślać pewność. Po zamknięciu danego checkpointu
wracam do ADR-a i dopisuję, co faktycznie wyszło — łącznie z przypadkami, w których
decyzja okazała się gorsza, niż zakładałem.

| ADR | Decyzja | Status |
|---|---|---|
| [0001](0001-crdt-vs-ot.md) | RGA (CRDT) zamiast OT | Zaakceptowany |
| [0002](0002-transport.md) | WebSocket + STOMP | Zaakceptowany |
| [0003](0003-historia-zmian.md) | Event log jako źródło prawdy + snapshoty jako cache | Zaakceptowany |
| [0004](0004-skalowanie-poziome.md) | Instancja backendu jako replika CRDT, Redis jako zawodna ścieżka szybka | Zaakceptowany |
| [0005](0005-tombstone-gc.md) | GC oparty na stabilności przyczynowej, z jawnym odrzucaniem bardzo starych klientów | Zaakceptowany |
| [0006](0006-warstwa-edytora.md) | CodeMirror 6 jako warstwa widoku, model dokumentu własny | Zaakceptowany |
| [0007](0007-zakres-ui.md) | Język wizualny mockupu przyjęty, jego zakres funkcjonalny odrzucony | **Zastąpiony przez [0008](0008-rozszerzenie-zakresu.md)** |
| [0008](0008-rozszerzenie-zakresu.md) | Rozszerzenie zakresu z artefaktu do produktu | Zaakceptowany |
| [0009](0009-model-uprawnien.md) | Role uporządkowane, egzekwowane w obu warstwach z jednej reguły | Zaakceptowany |
| [0010](0010-rich-text.md) | Formatowanie jako osobna warstwa CRDT (Peritext), nie atrybut znaku | Zaakceptowany |
| [0011](0011-komentarze.md) | Kotwica komentarza w CRDT, treść rozmowy poza nim | Zaakceptowany |
| [0012](0012-autoryzacja-strukturalna.md) | Autoryzacja deklarowana przy endpoincie i wymuszana konstrukcją — endpoint bez deklaracji nie jest obsługiwany | Zaakceptowany |
