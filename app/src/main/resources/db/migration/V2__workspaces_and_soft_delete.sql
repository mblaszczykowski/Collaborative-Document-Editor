-- CP11 — wiele dokumentów. Schemat z V1 wystarczał dla jednego wspólnego dokumentu;
-- tutaj dochodzi to, co jest potrzebne, żeby dokumenty dało się od siebie odróżnić,
-- pogrupować i usunąć bez niszczenia źródła prawdy.

CREATE TABLE workspaces (
    id         UUID PRIMARY KEY,
    name       TEXT        NOT NULL,
    owner_id   TEXT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX workspaces_by_owner ON workspaces (owner_id);

ALTER TABLE documents
    -- NULL oznacza dokument spoza workspace'u ("prywatny"). Kolumna jest nullowalna
    -- celowo: dokumenty utworzone przed tą migracją do żadnego workspace'u nie należą,
    -- a przypisanie ich do sztucznego "domyślnego" byłoby wymyślaniem faktu,
    -- którego nie ma. Zapytania o listę muszą więc obsłużyć NULL i to jest jawne.
    ADD COLUMN workspace_id UUID REFERENCES workspaces (id) ON DELETE SET NULL,

    -- USUWANIE MIĘKKIE, nie DELETE.
    --
    -- Tabela operations ma ON DELETE CASCADE na doc_id, więc twarde usunięcie dokumentu
    -- skasowałoby jego log operacji — czyli ŹRÓDŁO PRAWDY (ADR-0003), które w tym systemie
    -- jest wyłącznie dopisywane i nigdy nie kasowane. Dokument usunięty ma być niedostępny,
    -- a nie nieodwracalnie zniszczony; to dwie różne rzeczy i tylko pierwszej chce
    -- użytkownik klikający "usuń".
    ADD COLUMN deleted_at TIMESTAMPTZ,

    -- CACHE treści na potrzeby listy dokumentów. Materializowany przy snapshotowaniu.
    --
    -- Bez tego lista dokumentów musiałaby dla każdej pozycji załadować replikę CRDT
    -- do pamięci, żeby pokazać trzy pierwsze linijki — czyli otwarcie listy stu dokumentów
    -- ładowałoby sto replik. To jest ta sama pułapka, przed którą chroni traktowanie
    -- snapshotu jako cache'u, więc obowiązuje tu ta sama zasada: WOLNO to skasować
    -- w każdej chwili, a system odtworzy podgląd przy następnym snapshocie.
    ADD COLUMN preview_text TEXT NOT NULL DEFAULT '';

-- Lista dokumentów pyta zawsze o nieusunięte, posortowane po dacie zmiany. Indeks
-- częściowy, bo dokumenty usunięte nie pojawiają się na żadnej liście poza koszem —
-- nie ma powodu, żeby zajmowały miejsce w indeksie obsługującym ścieżkę gorącą.
CREATE INDEX documents_live_by_updated
    ON documents (updated_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX documents_by_workspace
    ON documents (workspace_id, updated_at DESC)
    WHERE deleted_at IS NULL;

-- Wyszukiwanie pełnotekstowe po tytule i podglądzie treści.
--
-- 'simple' zamiast 'polish': konfiguracja polska nie jest w standardowej dystrybucji
-- Postgresa (wymaga słowników ispell), więc obraz z docker-compose.yml by jej nie miał
-- i migracja padłaby przy starcie. 'simple' nie robi stemmingu, czyli "dokumentu"
-- nie znajdzie po "dokument" — to jest znane ograniczenie, nie przeoczenie, i wchodzi
-- do sekcji "co zrobiłbym inaczej w produkcji".
CREATE INDEX documents_search
    ON documents
    USING gin (to_tsvector('simple', title || ' ' || preview_text))
    WHERE deleted_at IS NULL;
