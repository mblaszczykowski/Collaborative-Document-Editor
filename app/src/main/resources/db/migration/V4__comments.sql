-- CP14 — komentarze.
--
-- Komentarz składa się z dwóch rzeczy, z których TYLKO JEDNA jest CRDT:
--
--   KOTWICA  — zakres w dokumencie. To jest znacznik typu `comment` z identyfikatorem wątku
--              jako wartością, czyli dokładnie ta sama maszyneria, którą wprowadził CP13
--              (ADR-0010). Ten checkpoint jej UŻYWA, nie buduje. Nie ma dla niej tabeli,
--              bo żyje w logu operacji jak każda inna operacja CRDT.
--
--   TREŚĆ    — wiadomości w wątku. Poniższe tabele.
--
-- DLACZEGO TREŚĆ NIE JEST CRDT. Wiadomość ma jednego autora, powstaje w całości i nie jest
-- współbieżnie edytowana znak po znaku. CRDT rozwiązuje konkretny problem — współbieżną
-- edycję tej samej sekwencji — i stosowanie go tam, gdzie tego problemu nie ma, to koszt
-- bez korzyści: log operacji, scalanie, tombstone'y i GC dla tekstu, który nikt nigdy
-- nie edytuje równolegle.
--
-- Pokusa „skoro mamy CRDT, wszystko będzie CRDT" jest realna i to jest miejsce, w którym
-- świadomie jej nie ulegamy.

CREATE TABLE comment_threads (
    id          UUID PRIMARY KEY,
    doc_id      UUID        NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    created_by  TEXT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Rozwiązanie wątku, nie jego usunięcie. Rozmowa zostaje — zmienia się tylko to,
    -- czy jest domyślnie widoczna. Ta sama zasada, co przy miękkim usuwaniu dokumentów.
    resolved_at TIMESTAMPTZ,
    resolved_by TEXT REFERENCES users (id) ON DELETE SET NULL,

    -- Kopia tekstu, którego wątek dotyczy, w chwili jego założenia.
    --
    -- Redundantna wobec kotwic i CELOWO redundantna: gdy zakotwiczony tekst zostanie
    -- skasowany, wątek staje się „odłączony" i bez tej kopii nie da się pokazać, czego
    -- dotyczył. Rozmowa o nieznanym fragmencie jest niewiele warta.
    quoted_text TEXT        NOT NULL DEFAULT ''
);

CREATE INDEX comment_threads_by_document
    ON comment_threads (doc_id, created_at)
    WHERE resolved_at IS NULL;

CREATE TABLE comment_messages (
    id        UUID PRIMARY KEY,
    thread_id UUID        NOT NULL REFERENCES comment_threads (id) ON DELETE CASCADE,
    author_id TEXT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    body      TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    edited_at  TIMESTAMPTZ
);

CREATE INDEX comment_messages_by_thread ON comment_messages (thread_id, created_at);
