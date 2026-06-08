-- CP12 — model uprawnień.
--
-- Do tej pory model dostępu brzmiał „każdy zalogowany może wszystko z każdym dokumentem"
-- i było to poprawne, bo dokument był jeden i wspólny. Od CP11 dokumentów jest wiele,
-- więc ten sam model przestaje być uproszczeniem, a staje się podatnością.

-- Właściciel dokumentu. NULL dla dokumentów sprzed tej migracji — nie wymyślamy im
-- właściciela, bo informacji o tym, kto je utworzył, nigdy nie było.
ALTER TABLE documents
    ADD COLUMN owner_id TEXT REFERENCES users (id) ON DELETE SET NULL,

    -- WIDOCZNOŚĆ. Domyślnie 'link', czyli „każdy, kto ma adres, może edytować".
    --
    -- To jest świadomy wybór wartości domyślnej, nie przeoczenie. Odpowiada dokładnie
    -- temu, jak aplikacja zachowywała się do tej pory, więc migracja niczego nie psuje
    -- ani nikomu nie odbiera dostępu — a jednocześnie mechanizm uprawnień jest realny
    -- i da się go włączyć per dokument. Odpowiednik dokumentu Google z ustawieniem
    -- „każdy z linkiem może edytować".
    --
    -- 'private' oznacza: wyłącznie właściciel i osoby wymienione w document_members.
    ADD COLUMN visibility TEXT NOT NULL DEFAULT 'link'
        CHECK (visibility IN ('private', 'link'));

CREATE INDEX documents_by_owner ON documents (owner_id) WHERE deleted_at IS NULL;

-- V1 utworzyło document_members bez roli — wtedy członkostwo znaczyło tylko „widziałem
-- ten dokument". Teraz niesie uprawnienie.
--
-- Role są uporządkowane rosnąco i to uporządkowanie jest częścią modelu:
--   viewer    — czyta
--   commenter — czyta i komentuje (CP14), NIE zmienia tekstu
--   editor    — zmienia tekst i formatowanie
--   owner     — dodatkowo zarządza dostępem
ALTER TABLE document_members
    ADD COLUMN role TEXT NOT NULL DEFAULT 'editor'
        CHECK (role IN ('viewer', 'commenter', 'editor', 'owner'));

-- Uprawnienia na poziomie workspace'u, dziedziczone przez jego dokumenty.
--
-- Dziedziczenie jest nadpisywalne wpisem w document_members — inaczej nie dałoby się
-- odebrać komuś dostępu do JEDNEGO dokumentu bez wyrzucania go z całego workspace'u.
CREATE TABLE workspace_members (
    workspace_id UUID        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    user_id      TEXT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role         TEXT        NOT NULL CHECK (role IN ('viewer', 'commenter', 'editor', 'owner')),
    added_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, user_id)
);

CREATE INDEX workspace_members_by_user ON workspace_members (user_id);
CREATE INDEX document_members_by_user ON document_members (user_id);
