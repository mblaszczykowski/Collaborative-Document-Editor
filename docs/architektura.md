# Diagramy

## Architektura

```mermaid
flowchart TB
    subgraph browser["Przeglądarka"]
        CM["CodeMirror 6<br/><i>tylko warstwa widoku</i>"]
        CRDT_TS["crdt/<br/><b>replika RGA</b><br/>+ warstwa znaczników"]
        BUF["bufor offline<br/>IndexedDB"]
        SIM["symulator sieci"]
        CM <--> CRDT_TS
        CRDT_TS --> BUF
        CRDT_TS --> SIM
    end

    subgraph instance1["Instancja backendu #1"]
        AUTH1["autoryzacja ramki<br/><i>rola → typ operacji</i>"]
        WS1["STOMP"]
        REP1["replika RGA<br/>+ znaczniki"]
        WS1 --> AUTH1
        AUTH1 <--> REP1
    end

    subgraph instance2["Instancja backendu #2"]
        WS2["STOMP"]
        REP2["replika RGA<br/>+ znaczniki"]
        WS2 <--> REP2
    end

    PG[("PostgreSQL<br/><b>log operacji</b><br/>ŹRÓDŁO PRAWDY")]
    RD[("Redis pub/sub<br/><i>ścieżka szybka<br/>ZAWODNA</i>")]

    SIM <-->|"WebSocket / STOMP"| WS1
    REP1 -->|"zapis"| PG
    REP2 -->|"zapis"| PG
    REP1 <-->|"best-effort"| RD
    REP2 <-->|"best-effort"| RD
    PG -.->|"anti-entropy<br/>naprawa luk"| REP1
    PG -.->|"anti-entropy<br/>naprawa luk"| REP2

    style PG fill:#2E8B6B,color:#fff
    style RD fill:#B8862E,color:#fff
    style CRDT_TS fill:#4C5FD5,color:#fff
    style REP1 fill:#4C5FD5,color:#fff
    style REP2 fill:#4C5FD5,color:#fff
    style AUTH1 fill:#C1592E,color:#fff
```

Wszystkie cztery repliki RGA na diagramie — dwie w przeglądarkach i dwie w instancjach
backendu — są **równorzędne**. Nie ma właściciela dokumentu ani arbitra kolejności
([ADR-0004](adr/0004-skalowanie-poziome.md)).

Autoryzacja jest narysowana tylko przy jednej instancji, żeby nie zaciemniać rysunku, ale
działa w każdej: **każda ramka SUBSCRIBE i SEND** przechodzi przez sprawdzenie roli
([ADR-0009](adr/0009-model-uprawnien.md)). Nie jest to sprawdzenie „czy masz dostęp
do dokumentu", tylko „czy Twoja rola pozwala na TĘ operację" — komentarz i edycja idą tym
samym kanałem, więc rozróżnienie musi zejść do typu operacji.

---

## Droga pojedynczej edycji

```mermaid
sequenceDiagram
    autonumber
    participant A as Anna<br/>(przeglądarka)
    participant S as Instancja #1
    participant DB as Log operacji
    participant R as Redis
    participant S2 as Instancja #2
    participant M as Marek<br/>(przeglądarka)

    A->>A: wstawienie do lokalnej repliki<br/>(tekst widoczny NATYCHMIAST)
    A->>S: operacja + traceparent<br/>w nagłówku ramki
    S->>S: autoryzacja ramki<br/>(rola → typ operacji)
    S->>S: scalenie do repliki
    S-->>M: rozgłoszenie (wsadowo, okno 10 ms)
    Note over S,M: krok przed zapisem — to on<br/>odpowiada za to, co widać

    S->>DB: zapis wsadowy (co 50 ms)
    DB-->>S: nadany server_seq
    S-->>A: POTWIERDZENIE (server_seq)
    Note over A: dopiero teraz operacja<br/>opuszcza bufor offline

    S->>R: publikacja (może zginąć)
    R-->>S2: operacja
    S2->>S2: scalenie
    S2-->>M: rozgłoszenie

    rect rgb(255, 245, 235)
        Note over S2,DB: gdy wiadomość zginie:<br/>luka w server_seq → dociągnięcie z logu
        S2->>DB: brakujący zakres
        DB-->>S2: operacje
    end
```

Kolejność kroków nie jest dowolna — uzasadnienie każdego z nich w
[ADR-0003](adr/0003-historia-zmian.md) i [ADR-0004](adr/0004-skalowanie-poziome.md).

---

## Stany połączenia klienta

```mermaid
stateDiagram-v2
    [*] --> Łączenie
    Łączenie --> Resynchronizacja: połączono
    Resynchronizacja --> Online: dosłano brakujące operacje

    Online --> Offline: zerwane połączenie<br/>lub brak heartbeatu (25 s)
    Offline --> Łączenie: ponowna próba<br/>(odstęp wykładniczy + jitter)

    Online --> Resynchronizacja: WYKRYTA LUKA<br/>potwierdzenie operacji,<br/>której nie mamy

    Online --> OdzyskanySzkic: RESYNC_REQUIRED<br/>GC usunął punkt zaczepienia
    OdzyskanySzkic --> [*]: lokalna wersja zapisana<br/>jako osobny dokument

    note right of Offline
        Edycja działa dalej.
        Operacje trafiają do IndexedDB
        i przeżywają zamknięcie karty.
    end note

    note right of Online
        Wykrywanie luk opiera się na tym,
        że potwierdzenie niesie TOŻSAMOŚĆ
        operacji — kursor server_seq
        by tu nie wystarczył.
    end note
```

---

## Komentarz — dwie drogi, z których tylko jedna jest CRDT

Najczęstsze nieporozumienie przy czytaniu tego kodu brzmi: „skoro macie CRDT, to komentarze
też są w CRDT". Nie są — i [ADR-0011](adr/0011-komentarze.md) tłumaczy dlaczego. Rysunek
pokazuje, co z tego wynika w przepływie.

```mermaid
sequenceDiagram
    autonumber
    participant M as Marek<br/>(przeglądarka)
    participant REST as REST<br/>/api/comments
    participant DB as PostgreSQL<br/>tabele rozmów
    participant WS as STOMP<br/>/app/doc/{id}/op
    participant A as Anna<br/>(przeglądarka)

    M->>REST: treść wątku + cytat
    REST->>DB: INSERT wątek + wiadomość
    DB-->>REST: identyfikator wątku
    REST-->>M: identyfikator wątku

    Note over M,WS: dopiero TERAZ powstaje kotwica —<br/>potrzebuje identyfikatora wątku

    M->>WS: AddMark(type=comment,<br/>value=id wątku)
    WS-->>A: rozgłoszenie operacji
    A->>A: podświetlenie fragmentu<br/>NATYCHMIAST

    Note over A,REST: treść rozmowy dociąga się osobno,<br/>przy odświeżeniu panelu
    A->>REST: lista wątków dokumentu
    REST-->>A: rozmowy
```

Widać w tym dwie rzeczy, które łatwo przeoczyć w kodzie:

1. **Kotwica powstaje PO wątku**, bo potrzebuje jego identyfikatora. Między jednym a drugim
   jest okno, w którym wątek istnieje bez kotwicy — i to jest znana, opisana konsekwencja
   tego podziału, a nie przeoczenie.
2. **Podświetlenie i treść docierają różnymi drogami i w różnym tempie.** Podświetlenie jest
   operacją CRDT, więc pojawia się u drugiej osoby natychmiast; rozmowa idzie REST-em i czeka
   na odświeżenie panelu. Wygląda to na niespójność i nią nie jest: scalać bez arbitra musi
   się zakres, a nie zdanie napisane przez jedną osobę.
