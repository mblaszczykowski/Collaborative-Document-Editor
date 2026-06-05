Collaborative Document Editor — opis projektu
Koncepcja
Edytor tekstu w czasie rzeczywistym (Google Docs light), umożliwiający wielu użytkownikom jednoczesną edycję tego samego dokumentu bez konfliktów i utraty danych — nawet przy słabym połączeniu, jednoczesnej edycji tego samego fragmentu tekstu czy chwilowym rozłączeniu klienta.
Problem, który rozwiązuje
Naiwna synchronizacja tekstu (np. "ostatni zapis wygrywa") prowadzi do utraty zmian, gdy dwóch userów edytuje ten sam fragment jednocześnie. Projekt pokazuje, jak zbudować system, w którym zmiany od wielu klientów zawsze się bezkonfliktowo scalają, zachowując intencję każdego autora — to klasyczny, twardy problem inżynierii rozproszonej (distributed consistency bez centralnego lockowania).
Architektura i podejście techniczne
Algorytm synchronizacji — do wyboru/porównania:
CRDT (Conflict-free Replicated Data Type) — np. struktura oparta na RGA/Peritext, gdzie każdy znak ma unikalny identyfikator pozycyjny; scalanie jest matematycznie gwarantowane bez centralnego arbitra
Operational Transform (OT) — transformacja operacji względem siebie na serwerze (podejście używane historycznie przez Google Docs)
Rekomendacja: zacząć od CRDT (prostsze do poprawnego zaimplementowania samodzielnie, mniej "magicznych" edge case'ów niż OT) i w README opisać świadomie, dlaczego ten wybór, z porównaniem trade-offów obu podejść.
Stack:
Backend: Java (Spring Boot) + WebSocket (STOMP) do propagacji operacji w czasie rzeczywistym
Persystencja: PostgreSQL — snapshoty dokumentu + log operacji (event sourcing dla historii zmian)
Frontend: React + TypeScript, custom text editor (np. na bazie ContentEditable lub biblioteki typu Slate.js/ProseMirror jako warstwa UI, z własną logiką CRDT pod spodem)
Reconnect handling: bufor operacji offline + resynchronizacja po powrocie połączenia
Kluczowe funkcjonalności
Edycja wieloosobowa w czasie rzeczywistym — widoczne kursory i zaznaczenia innych userów (jak w Google Docs)
Historia zmian / wersjonowanie — możliwość cofnięcia się do dowolnego punktu w czasie, z widokiem "kto co zmienił" (audit trail)
Obsługa offline i reconnect — edycja działa lokalnie przy braku sieci, po powrocie połączenia zmiany scalają się automatycznie
Presence — lista aktywnych userów w dokumencie, statusy "kto teraz pisze"
Symulator konfliktów — tryb demo pokazujący świadomie sztuczne opóźnienia sieciowe i jednoczesną edycję tego samego fragmentu, żeby zademonstrować poprawność algorytmu (to bardzo mocno "sprzedaje" projekt na rozmowie rekrutacyjnej)
Co ten projekt demonstruje (senior signal)
Zrozumienie teorii spójności danych rozproszonych (CAP theorem w praktyce, eventual consistency)
Umiejętność zaimplementowania nietrywialnego algorytmu od podstaw, a nie tylko użycia gotowej biblioteki
Projektowanie protokołu komunikacji real-time (WebSocket) z myśleniem o edge case'ach: race conditions, network partition, reconnect
Full-stack: złożona logika domenowa na backendzie + wymagający, stanowy UI na froncie
Ryzyka / na co uważać
CRDT dla tekstu jest trudniejsze niż się wydaje na pierwszy rzut oka (memory overhead tombstone'ów, wydajność przy długich dokumentach). Warto ograniczyć scope do prostego edytora tekstu (bez rich formatting na start) i jasno opisać w README świadome uproszczenia oraz "co zrobiłbym inaczej w produkcji" — to samo w sobie jest senior-level sygnałem.
