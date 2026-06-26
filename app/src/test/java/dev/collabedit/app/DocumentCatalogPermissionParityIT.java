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

/**
 * The access rule exists twice, and this is what keeps the two copies honest.
 *
 * <h2>Why there are two copies at all</h2>
 * {@link PermissionService} resolves a role for ONE document, in Java. {@link DocumentCatalog}
 * needs the same question answered for MANY documents inside a query, because the alternative —
 * which is what the code used to do — is to take the 200 most recently updated documents on the
 * instance and filter them afterwards. On a busy instance that made a user's own documents vanish
 * from their own first screen, silently: the {@code LIMIT} was applied before the filter, so the
 * global slice simply did not contain them.
 *
 * <p>The comment the old code carried was right about the risk and wrong about the remedy: "two
 * copies of an access rule drift apart, and then one of them lets too much through". Two copies do
 * drift — unless something fails when they do. That is this test.
 *
 * <h2>What it checks</h2>
 * Every combination of the four inputs the rule reads: ownership, a document-level membership, a
 * workspace-level membership, and visibility. For each one it asserts that
 * {@code catalog.list(...)} includes the document exactly when {@code permissions.canRead(...)}
 * says it may be read. Not "produces a sensible answer" — the SAME answer.
 *
 * <p>The direction of a disagreement matters and the assertion is deliberately symmetric. The SQL
 * being too narrow hides a document from its owner; the SQL being too wide would show one that is
 * not theirs. The controller's Java filter still runs afterwards, so only the first can actually
 * reach a user — but a test that checked one direction would leave the other free to rot.
 */
class DocumentCatalogPermissionParityIT extends IntegrationTestBase {

    @Autowired
    DocumentCatalog catalog;

    @Autowired
    PermissionService permissions;

    @Autowired
    JdbcTemplate jdbc;

    /** One combination of the inputs the access rule reads. */
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

            // An IMPLICATION, not an equivalence: everything listed must be readable. The reverse
            // does not hold and must not be asserted — see the class javadoc.
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

        // The endpoint accepts `q` and `workspace` together and used to drop the second silently,
        // so "search inside this workspace" searched every workspace. The null case is the one the
        // other tests exercise; this is the one that needs `CAST(? AS uuid)` to bind a real value.
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

        // Scoped to a workspace of its own. The suite shares one database, so an unscoped listing
        // would also be competing with every document the other tests happen to have left behind —
        // which would make this test's result depend on execution order rather than on the rule.
        UUID workspace = UUID.randomUUID();
        jdbc.update("INSERT INTO workspaces (id, name, owner_id) VALUES (?, ?, ?)",
            workspace, "Shared workspace", stranger);

        UUID mine = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, title, workspace_id, owner_id, visibility, updated_at) "
                + "VALUES (?, ?, ?, ?, 'private', now() - interval '1 day')",
            mine, "Mine", workspace, caller);

        // A full page of somebody else's PRIVATE documents, all updated more recently. Before the
        // predicate moved into the query these filled the LIMIT and the caller's own document —
        // the only one they can see — never made it into the result at all.
        int limit = 20;
        for (int i = 0; i < limit; i++) {
            jdbc.update("INSERT INTO documents (id, title, workspace_id, owner_id, visibility) "
                    + "VALUES (?, ?, ?, ?, 'private')",
                UUID.randomUUID(), "Theirs " + i, workspace, stranger);
        }

        assertThat(catalog.list(caller, workspace, limit))
            .as("the caller's own document fell out of a page filled with documents they cannot "
                + "see — the failure is silent and gets worse as the instance gets busier")
            .extracting(DocumentCatalog.DocumentSummary::id)
            .contains(mine);
    }

    /**
     * The property the equivalence used to forbid, and the reason it had to stop being one.
     *
     * <p>This test asserted that listing and access AGREE on every combination. That reads like
     * rigour and was in fact pinning a defect: it forced the list to include everything the caller
     * could open, and {@code link} visibility means anyone logged in can open it — so every
     * sidebar listed every link-visible document in the database, and login is passwordless.
     *
     * <p>The two are different questions. {@code link} means <i>anyone with the link</i>:
     * possession of the address is the credential, and a list that hands out addresses gives the
     * credential away. So access stays exactly as it was, and discovery does not.
     */
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
            .as("link visibility stopped granting access — somebody holding the link can no "
                + "longer open it, which is the whole mode")
            .isTrue();

        assertThat(catalog.list(stranger, null, 500))
            .as("""
                A document nobody shared with this person appeared in their list. That is how \
                every user of this application came to see every document in it: the list was \
                asking "could you open this" instead of "is this yours".""")
            .noneMatch(summary -> summary.id().equals(document));

        assertThat(catalog.list(owner, null, 500))
            .as("the owner lost sight of their own document — the fix narrowed too far")
            .anyMatch(summary -> summary.id().equals(document));
    }

}
