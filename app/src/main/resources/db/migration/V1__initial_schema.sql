-- Schemat wynika wprost z ADR-0003 (log operacji jako źródło prawdy, snapshoty jako cache)
-- i ADR-0004 (server_seq jako kursor dla resynchronizacji i anti-entropy).

CREATE TABLE documents (
    id              UUID PRIMARY KEY,
    title           TEXT        NOT NULL,
    -- Licznik server_seq trzymany przy dokumencie, a nie jako sekwencja Postgresa.
    -- Powód: sekwencja zostawia dziury przy wycofanej transakcji, a ADR-0004 opiera
    -- wykrywanie zgubionych operacji na ciągłości tego licznika. Ceną jest blokada
    -- wiersza przy zapisie, czyli serializacja zapisów w obrębie JEDNEGO dokumentu —
    -- akceptowalna, bo zapisy i tak są wsadowane co 50 ms (application.yml).
    next_server_seq BIGINT      NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ŹRÓDŁO PRAWDY. Tabela wyłącznie dopisywana — nigdy UPDATE, nigdy DELETE.
-- Przywrócenie starej wersji dokumentu zapisujemy jako NOWE operacje (ADR-0003 pkt 6),
-- bo obcięcie logu zniszczyłoby audytowalność i rozspójniło podłączonych klientów.
CREATE TABLE operations (
    doc_id     UUID        NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    replica_id INTEGER     NOT NULL,
    lamport    BIGINT      NOT NULL,
    server_seq BIGINT      NOT NULL,
    op_type    CHAR(1)     NOT NULL,
    payload    JSONB       NOT NULL,
    user_id    TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Klucz główny to identyfikator operacji CRDT. Dzięki temu idempotencja zapisu
    -- jest własnością schematu, a nie kodu deduplikującego: retransmisja po ponownym
    -- połączeniu po prostu nie może utworzyć drugiego wiersza.
    PRIMARY KEY (doc_id, replica_id, lamport)
);

-- Kursor resynchronizacji: "daj mi wszystko powyżej server_seq X".
-- UNIQUE, bo dwie operacje o tym samym server_seq oznaczałyby błąd przydziału licznika.
CREATE UNIQUE INDEX operations_by_seq ON operations (doc_id, server_seq);

-- CACHE. Wolno skasować w każdej chwili — system odtworzy stan z logu, wolniej,
-- ale poprawnie. Test w CP5 kasuje wszystkie snapshoty i sprawdza, że żaden wynik
-- się nie zmienia; bez takiego testu cache po cichu stałby się źródłem prawdy.
CREATE TABLE snapshots (
    doc_id           UUID        NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    up_to_server_seq BIGINT      NOT NULL,
    -- Wersja formatu serializacji. Przy niezgodności snapshot jest IGNOROWANY,
    -- a nie migrowany — migracja cache'a to koszt bez korzyści, skoro log wystarczy.
    format_version   INTEGER     NOT NULL,
    state            BYTEA       NOT NULL,
    -- Metryki narzutu tombstone'ów (ADR-0005) zapisane w chwili snapshotu, żeby dało
    -- się je pokazać na wykresie bez ładowania całego stanu do pamięci.
    text_length      INTEGER     NOT NULL,
    tombstone_count  INTEGER     NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (doc_id, up_to_server_seq)
);

-- Uproszczony model użytkowników i dostępu. Świadomie minimalny — pełny model ról
-- i udostępniania jest poza zakresem projektu (README, "co zrobiłbym inaczej").
CREATE TABLE users (
    id           TEXT PRIMARY KEY,
    display_name TEXT        NOT NULL,
    color        TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE document_members (
    doc_id    UUID        NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    user_id   TEXT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (doc_id, user_id)
);
