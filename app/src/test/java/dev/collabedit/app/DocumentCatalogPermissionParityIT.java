package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.app.auth.PermissionService;
import dev.collabedit.app.persist.DocumentCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class DocumentCatalogPermissionParityIT extends IntegrationTestBase {
    @Autowired
    DocumentCatalog catalog;

    @Autowired
    PermissionService permissions;

    @Autowired
    JdbcTemplate jdbc;

    private record Scenario(boolean owner, String documentRole, String workspaceRole,
                            String visibility) {
        @Override
        public String toString() {
            return "owner=" + owner + " documentRole=" + documentRole
                + " workspaceRole=" + workspaceRole + " visibility=" + visibility;
        }
    }

    private static List<Scenario> scenarios() {
        List<Scenario> all = new ArrayList<>();
        for (boolean owner : new boolean[] {false, true}) {
            for (String documentRole : new String[] {null, "viewer", "commenter", "editor"}) {
                for (String workspaceRole : new String[] {null, "viewer", "editor"}) {
                    for (String visibility : new String[] {"private", "link"}) {
                        all.add(new Scenario(owner, documentRole, workspaceRole, visibility));
                    }
                }
            }
        }
        return all;
    }

    @Test
    @DisplayName("the catalog's SQL predicate and PermissionService agree on every combination")
    void theTwoCopiesOfTheAccessRuleAgree() {
        List<String> disagreements = new ArrayList<>();

        for (Scenario scenario : scenarios()) {
            String caller = "user-" + UUID.randomUUID();
            String owner = scenario.owner() ? caller : "user-" + UUID.randomUUID();
            UUID workspace = UUID.randomUUID();
            UUID document = UUID.randomUUID();

            jdbc.update("INSERT INTO users (id, display_name, color) VALUES (?, ?, ?)",
                caller, "Caller", "#4C5FD5");
            if (!scenario.owner()) {
                jdbc.update("INSERT INTO users (id, display_name, color) VALUES (?, ?, ?)",
                    owner, "Owner", "#C1592E");
            }
            jdbc.update("INSERT INTO workspaces (id, name, owner_id) VALUES (?, ?, ?)",
                workspace, "Workspace", owner);
            jdbc.update(
                "INSERT INTO documents (id, title, workspace_id, owner_id, visibility) "
                    + "VALUES (?, ?, ?, ?, ?)",
                document, "Doc " + scenario, workspace, owner, scenario.visibility());

            if (scenario.documentRole() != null) {
                jdbc.update("INSERT INTO document_members (doc_id, user_id, role) VALUES (?, ?, ?)",
                    document, caller, scenario.documentRole());
            }
            if (scenario.workspaceRole() != null) {
                jdbc.update(
                    "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, ?)",
                    workspace, caller, scenario.workspaceRole());
            }

            boolean listed = catalog.list(caller, workspace, 500).stream()
                .anyMatch(summary -> summary.id().equals(document));
            boolean readable = permissions.canRead(caller, document);

            if (listed && !readable) {
                disagreements.add(scenario + " -> listed but NOT readable");
            }
        }

        assertThat(disagreements)
            .as("DocumentCatalog's SQL listed a document PermissionService would refuse. That is "
                + "the leak direction: the list is the one screen that hands out identifiers, and "
                + "only the controller's second filter is now stopping it")
            .isEmpty();
    }

    @Test
    @DisplayName("search honours the workspace it was given, and finds nothing outside it")
    void searchIsScopedToTheWorkspace() {
        String caller = "user-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, display_name, color) VALUES (?, ?, ?)",
            caller, "Caller", "#4C5FD5");

        UUID mine = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        for (UUID workspace : new UUID[] {mine, other}) {
            jdbc.update("INSERT INTO workspaces (id, name, owner_id) VALUES (?, ?, ?)",
                workspace, "Workspace " + workspace, caller);
        }

        String needle = "quasar" + UUID.randomUUID().toString().replace("-", "");
        UUID inMine = UUID.randomUUID();
        UUID inOther = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, title, workspace_id, owner_id, visibility) "
            + "VALUES (?, ?, ?, ?, 'private')", inMine, needle + " here", mine, caller);
        jdbc.update("INSERT INTO documents (id, title, workspace_id, owner_id, visibility) "
            + "VALUES (?, ?, ?, ?, 'private')", inOther, needle + " elsewhere", other, caller);

        assertThat(catalog.search(caller, mine, needle, 50))
            .extracting(DocumentCatalog.DocumentSummary::id)
            .contains(inMine)
            .doesNotContain(inOther);

        assertThat(catalog.search(caller, null, needle, 50))
            .as("without a workspace the search should still span all of them")
            .extracting(DocumentCatalog.DocumentSummary::id)
            .contains(inMine, inOther);
    }

    @Test
    @DisplayName("a caller's own document is listed even when a full page of others is newer")
    void theLimitAppliesToWhatTheCallerCanSee() {
        String caller = "user-" + UUID.randomUUID();
        String stranger = "user-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, display_name, color) VALUES (?, ?, ?)",
            caller, "Caller", "#4C5FD5");
        jdbc.update("INSERT INTO users (id, display_name, color) VALUES (?, ?, ?)",
            stranger, "Stranger", "#C1592E");

        UUID workspace = UUID.randomUUID();
        jdbc.update("INSERT INTO workspaces (id, name, owner_id) VALUES (?, ?, ?)",
            workspace, "Shared workspace", stranger);

        UUID mine = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, title, workspace_id, owner_id, visibility, updated_at) "
                + "VALUES (?, ?, ?, ?, 'private', now() - interval '1 day')",
            mine, "Mine", workspace, caller);

        int limit = 20;
        for (int i = 0; i < limit; i++) {
            jdbc.update("INSERT INTO documents (id, title, workspace_id, owner_id, visibility) "
                    + "VALUES (?, ?, ?, ?, 'private')",
                UUID.randomUUID(), "Theirs " + i, workspace, stranger);
        }

        assertThat(catalog.list(caller, workspace, limit))
            .as("the caller's own document fell out of a page filled with documents they cannot "
                + "see - the failure is silent and gets worse as the instance gets busier")
            .extracting(DocumentCatalog.DocumentSummary::id)
            .contains(mine);
    }

    @Test
    @DisplayName("a link-visible document is readable by a stranger but listed for nobody but its own")
    void linkVisibilityGrantsAccessWithoutDiscovery() {
        String owner = "user-" + UUID.randomUUID();
        String stranger = "user-" + UUID.randomUUID();
        UUID document = UUID.randomUUID();

        jdbc.update("INSERT INTO users (id, display_name, color) VALUES (?, ?, ?)",
            owner, "Owner", "#4C5FD5");
        jdbc.update("INSERT INTO users (id, display_name, color) VALUES (?, ?, ?)",
            stranger, "Stranger", "#C1592E");
        jdbc.update("INSERT INTO documents (id, title, owner_id, visibility) VALUES (?, ?, ?, ?)",
            document, "Shared by link", owner, "link");

        assertThat(permissions.canRead(stranger, document))
            .as("link visibility stopped granting access - somebody holding the link can no "
                + "longer open it, which is the whole mode")
            .isTrue();

        assertThat(catalog.list(stranger, null, 500))
            .as("""
                A document nobody shared with this person appeared in their list. That is how \
                every user of this application came to see every document in it: the list was \
                asking "could you open this" instead of "is this yours".""")
            .noneMatch(summary -> summary.id().equals(document));

        assertThat(catalog.list(owner, null, 500))
            .as("the owner lost sight of their own document - the fix narrowed too far")
            .anyMatch(summary -> summary.id().equals(document));
    }
}
