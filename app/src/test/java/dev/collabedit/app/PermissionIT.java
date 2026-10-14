package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.app.auth.PermissionService;
import dev.collabedit.app.auth.Role;
import dev.collabedit.app.auth.TokenService;
import dev.collabedit.app.domain.DocumentService;
import dev.collabedit.app.persist.DocumentCatalog;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

class PermissionIT extends IntegrationTestBase {
    private static final Duration PATIENCE = Duration.ofSeconds(5);

    @LocalServerPort
    int port;

    @Autowired
    TokenService tokens;
    @Autowired
    PermissionService permissions;
    @Autowired
    DocumentCatalog catalog;
    @Autowired
    DocumentService documents;
    @Autowired
    JdbcTemplate jdbc;

    private record User(String id, String token) {
    }

    private User newUser(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO users (id, display_name, color) VALUES (?, ?, ?) "
            + "ON CONFLICT (id) DO NOTHING", id, name, "#4C5FD5");
        return new User(id, tokens.issue(new TokenService.Principal(id, name, "#4C5FD5")));
    }

    private UUID privateDocument(User owner, String title) {
        UUID id = UUID.randomUUID();
        catalog.create(id, title, null);
        permissions.setOwner(id, owner.id());
        permissions.setVisibility(id, "private");
        return id;
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(10))
            .DELETE();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(10));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("REST does not hand out the content of somebody else's private document")
    void restCannotReadForeignPrivateDocument() throws Exception {
        User anna = newUser("Anna");
        User marek = newUser("Marek");
        UUID document = privateDocument(anna, "Anna's private document");

        try (TestStompClient writer = new TestStompClient(port, anna.token(), document, 701)) {
            writer.insert(0, "secret content");
            writer.awaitCondition(() -> writer.lastAckSeq() > 0, PATIENCE, "write acknowledged");
        }

        assertThat(get("/api/documents/" + document, anna.token()).body())
            .as("the owner has to see their own document")
            .contains("secret content");

        HttpResponse<String> foreign = get("/api/documents/" + document, marek.token());
        assertThat(foreign.statusCode())
            .as("an unrelated user received the content of somebody else's private document (IDOR)")
            .isEqualTo(404);
        assertThat(foreign.body()).doesNotContain("secret content");

        assertThat(get("/api/documents/" + document, null).statusCode())
            .as("a request with no token must reauthenticate without learning whether the document exists")
            .isEqualTo(401);
    }

    @Test
    @DisplayName("WebSocket does not allow subscribing to somebody else's private document")
    void websocketCannotSubscribeToForeignPrivateDocument() throws Exception {
        User anna = newUser("Anna");
        User marek = newUser("Marek");
        UUID document = privateDocument(anna, "Anna's private document 2");

        try (TestStompClient owner = new TestStompClient(port, anna.token(), document, 702);
             TestStompClient intruder = new TestStompClient(port, marek.token(), document, 703)) {
            owner.insert(0, "text being watched");
            owner.awaitCondition(() -> owner.lastAckSeq() > 0, PATIENCE, "write acknowledged");
            Thread.sleep(500);

            assertThat(intruder.text())
                .as("an unrelated client saw the content of somebody else's private document LIVE")
                .isEmpty();
        }
    }

    @Test
    @DisplayName("a read-only role cannot change the content")
    void viewerCannotWriteText() throws Exception {
        User anna = newUser("Anna");
        User observer = newUser("Observer");
        UUID document = privateDocument(anna, "For reading");
        permissions.grant(document, observer.id(), Role.VIEWER);

        try (TestStompClient owner = new TestStompClient(port, anna.token(), document, 704)) {
            owner.insert(0, "original");
            owner.awaitCondition(() -> owner.lastAckSeq() > 0, PATIENCE, "write acknowledged");
        }

        try (TestStompClient viewer = new TestStompClient(port, observer.token(), document, 705)) {
            viewer.requestSync(0);
            viewer.awaitCondition(() -> viewer.text().equals("original"), PATIENCE,
                "the observer sees the content");

            viewer.insert(0, "VANDALISM");
            Thread.sleep(500);

            assertThat(documents.textOf(document))
                .as("a read-only role changed the document's content")
                .isEqualTo("original");
        }
    }

    @Test
    @DisplayName("a read-only role cannot FORMAT the text either")
    void viewerCannotFormatText() throws Exception {
        User anna = newUser("Anna");
        User observer = newUser("Observer");
        UUID document = privateDocument(anna, "For reading, not for bolding");
        permissions.grant(document, observer.id(), Role.VIEWER);

        try (TestStompClient owner = new TestStompClient(port, anna.token(), document, 706)) {
            owner.insert(0, "original");
            owner.awaitCondition(() -> owner.lastAckSeq() > 0, PATIENCE, "write acknowledged");
        }

        try (TestStompClient viewer = new TestStompClient(port, observer.token(), document, 707)) {
            viewer.requestSync(0);
            viewer.awaitCondition(() -> viewer.text().equals("original"), PATIENCE, "state fetched");

            viewer.markReturningOp(0, 5, "bold", null);
            Thread.sleep(500);
        }

        try (TestStompClient check = new TestStompClient(port, anna.token(), document, 708)) {
            check.requestSync(0);
            check.awaitCondition(() -> check.text().equals("original"), PATIENCE, "state fetched");
            assertThat(check.markCount())
                .as("a reader applied formatting - a read-only role changed the document")
                .isZero();
        }
    }

    @Test
    @DisplayName("revoking access takes effect")
    void revokingAccessTakesEffect() throws Exception {
        User anna = newUser("Anna");
        User marek = newUser("Marek");
        UUID document = privateDocument(anna, "Temporarily shared");
        permissions.grant(document, marek.id(), Role.EDITOR);

        assertThat(permissions.roleOf(marek.id(), document)).get()
            .matches(Role::canWriteText);

        permissions.revoke(document, marek.id());

        assertThat(permissions.canRead(marek.id(), document))
            .as("revoking access did nothing - the permission was remembered")
            .isFalse();
        assertThat(get("/api/documents/" + document, marek.token()).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("revoking access stops a subscription that is already live")
    void revokedAccessTerminatesLiveSubscription() throws Exception {
        User anna = newUser("Anna");
        User marek = newUser("Marek");
        UUID document = privateDocument(anna, "Shared, then not");
        permissions.grant(document, marek.id(), Role.EDITOR);

        try (TestStompClient owner = new TestStompClient(port, anna.token(), document, 741);
             TestStompClient guest = new TestStompClient(port, marek.token(), document, 742)) {
            owner.insert(0, "BEFORE");
            guest.awaitCondition(() -> guest.text().contains("BEFORE"), PATIENCE,
                "the guest receives broadcasts while they have access");

            int noticesBefore = guest.accessChangeCount();
            assertThat(delete("/api/documents/" + document + "/access/members/" + marek.id(),
                anna.token()).statusCode())
                .as("the owner revokes through the endpoint the interface actually calls")
                .isEqualTo(204);

            long before = owner.lastAckSeq();
            owner.insert(owner.text().length(), "AFTER");
            owner.awaitCondition(() -> owner.lastAckSeq() > before, PATIENCE,
                "the second write is acknowledged, so its broadcast has already happened");
            Thread.sleep(300);

            assertThat(guest.text())
                .as("""
                    A revoked user kept receiving the document's broadcasts on a subscription that \
                    was established before the revocation. Their writes are refused and their REST \
                    requests answer 404, so from the server's own logs the revocation looks \
                    complete - while they are watching the document being typed.""")
                .doesNotContain("AFTER");
            assertThat(guest.text())
                .as("what they already had is not the question here; what arrives afterwards is")
                .contains("BEFORE");

            guest.awaitCondition(() -> guest.accessChangeCount() > noticesBefore, PATIENCE,
                "the revoked client is never told its access changed, so it has no way to find "
                    + "out - the one frame whose purpose is to say so was the one being dropped");
        }
    }

    @Test
    @DisplayName("a document with 'link' visibility stays available to every logged-in user")
    void linkVisibilityKeepsSharedDocumentsWorking() throws Exception {
        User anna = newUser("Anna");
        User anybody = newUser("Anybody");

        UUID document = UUID.randomUUID();
        catalog.create(document, "Shared by link", null);
        permissions.setOwner(document, anna.id());
        permissions.setVisibility(document, "link");

        assertThat(permissions.roleOf(anybody.id(), document))
            .as("the migration took away access to a document that used to work")
            .contains(Role.EDITOR);
        assertThat(get("/api/documents/" + document, anybody.token()).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("a document in the bin is unreachable even for its owner, but visible in the bin")
    void deletedDocumentIsInvisibleEvenToOwner() throws Exception {
        User anna = newUser("Anna");
        UUID document = privateDocument(anna, "For the bin");

        catalog.softDelete(document);
        permissions.invalidate(document);

        assertThat(permissions.canRead(anna.id(), document))
            .as("a deleted document can still be opened by the ordinary path")
            .isFalse();
        assertThat(get("/api/documents/" + document, anna.token()).statusCode()).isEqualTo(404);

        assertThat(permissions.roleOfDeleted(anna.id(), document))
            .as("the owner cannot see their own document in the bin - it cannot be restored")
            .contains(Role.OWNER);
    }

    @Test
    @DisplayName("the initial state carries the caller's role, not the owner's")
    void openedDocumentCarriesCallersRole() throws Exception {
        User anna = newUser("Anna");
        User reader = newUser("Reader");
        User commenter = newUser("Commenter");
        UUID document = privateDocument(anna, "With roles");
        permissions.grant(document, reader.id(), Role.VIEWER);
        permissions.grant(document, commenter.id(), Role.COMMENTER);

        assertThat(get("/api/documents/" + document, anna.token()).body())
            .as("the owner did not receive their role")
            .contains("\"role\":\"owner\"");
        assertThat(get("/api/documents/" + document, reader.token()).body())
            .as("the reader received a role that permits writing")
            .contains("\"role\":\"viewer\"");
        assertThat(get("/api/documents/" + document, commenter.token()).body())
            .contains("\"role\":\"commenter\"");
    }

    @Test
    @DisplayName("history is not a third route into somebody else's document - for reading or writing")
    void historyEndpointsCheckAccess() throws Exception {
        User anna = newUser("Anna");
        User outsider = newUser("Outsider");
        User reader = newUser("Reader");
        UUID document = privateDocument(anna, "Private, with history");
        permissions.grant(document, reader.id(), Role.VIEWER);

        try (TestStompClient writer = new TestStompClient(port, anna.token(), document, 731)) {
            writer.insert(0, "secret content");
            writer.awaitCondition(() -> writer.lastAckSeq() > 0, PATIENCE, "write acknowledged");
        }

        assertThat(get("/api/documents/" + document + "/history", outsider.token()).statusCode())
            .as("the timeline of somebody else's private document is visible")
            .isEqualTo(404);
        assertThat(get("/api/documents/" + document + "/history/at?seq=1", outsider.token()).body())
            .as("history hands out the content of somebody else's private document")
            .doesNotContain("secret");

        assertThat(post("/api/documents/" + document + "/history/restore",
                reader.token(), "{\"throughServerSeq\":1}").statusCode())
            .as("a reader restored a version - a read-only role can change the content")
            .isEqualTo(404);
        assertThat(post("/api/documents/" + document + "/history/restore",
                outsider.token(), "{\"throughServerSeq\":1}").statusCode())
            .isEqualTo(404);

        assertThat(post("/api/documents/" + document + "/history/restore",
                anna.token(), "{\"throughServerSeq\":1}").statusCode())
            .as("the protection took access away from the owner too")
            .isEqualTo(200);
    }

    @Test
    @DisplayName("a per-document role overrides the one inherited from the workspace")
    void documentRoleOverridesWorkspaceRole() {
        User anna = newUser("Anna");
        User teamMember = newUser("Team member");

        UUID workspace = UUID.randomUUID();
        jdbc.update("INSERT INTO workspaces (id, name, owner_id) VALUES (?, ?, ?)",
            workspace, "The team", anna.id());
        jdbc.update("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, ?)",
            workspace, teamMember.id(), "editor");

        UUID document = privateDocument(anna, "The team's document");
        jdbc.update("UPDATE documents SET workspace_id = ? WHERE id = ?", workspace, document);
        permissions.invalidate(document);

        assertThat(permissions.roleOf(teamMember.id(), document))
            .as("workspace inheritance does not work - the team's document is invisible")
            .contains(Role.EDITOR);

        permissions.grant(document, teamMember.id(), Role.VIEWER);

        assertThat(permissions.roleOf(teamMember.id(), document))
            .as("the per-document role did not override the inherited one - restricting access does not work")
            .contains(Role.VIEWER);
        assertThat(permissions.roleOf(teamMember.id(), document)).get()
            .matches(role -> !role.canWriteText());
    }

    @Test
    @DisplayName("identity comes from the token, not from a request parameter")
    void identityComesFromTokenNotFromRequest() throws Exception {
        User anna = newUser("Anna");
        User snooper = newUser("Snooper");

        UUID workspace = UUID.randomUUID();
        jdbc.update("INSERT INTO workspaces (id, name, owner_id) VALUES (?, ?, ?)",
            workspace, "Anna's private team", anna.id());

        assertThat(get("/api/workspaces?owner=" + anna.id(), snooper.token()).body())
            .as("a request parameter decides whose workspaces the caller sees")
            .doesNotContain("Anna's private team");

        assertThat(get("/api/workspaces?owner=" + anna.id(), anna.token()).body())
            .as("the owner stopped seeing their own workspaces")
            .contains("Anna's private team");

        post("/api/workspaces", snooper.token(), "{\"name\":\"Planted\"}");
        assertThat(get("/api/workspaces", anna.token()).body())
            .as("an outsider created a workspace belonging to somebody else")
            .doesNotContain("Planted");
    }

    @Test
    @DisplayName("a recovered draft belongs to the person who recovered it, and is private")
    void recoveredDraftIsPrivateToItsOwner() throws Exception {
        User anna = newUser("Anna");
        User anybody = newUser("Anybody");

        String created = post("/api/documents/recovered", anna.token(),
            "{\"title\":\"Anna's recovered draft\",\"text\":\"offline work\"}").body();
        UUID id = UUID.fromString(created.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));

        assertThat(get("/api/documents/" + id, anybody.token()).statusCode())
            .as("the recovered draft is readable by every logged-in user")
            .isEqualTo(404);
        assertThat(get("/api/documents/" + id, anna.token()).body())
            .as("the person who recovered it has no access to their own draft")
            .contains("offline work");
        assertThat(permissions.roleOf(anna.id(), id))
            .as("the person who recovered it is not the owner, so cannot change the visibility")
            .contains(Role.OWNER);
    }

    @Test
    @DisplayName("a recovered draft cannot be created without an identity")
    void recoveredDraftCannotBeCreatedAnonymously() throws Exception {
        HttpResponse<String> anonymous = post("/api/documents/recovered", null,
            "{\"title\":\"Draft with no identity\",\"text\":\"offline work\"}");

        assertThat(anonymous.statusCode())
            .as("an anonymous caller created a document; it has no owner, so nobody can ever "
                + "secure it, and it is link-visible to every logged-in user")
            .isEqualTo(401);

        User anybody = newUser("Anybody");
        assertThat(get("/api/documents", anybody.token()).body())
            .as("the ownerless draft is readable by an unrelated user")
            .doesNotContain("Draft with no identity");
    }

    @Test
    @DisplayName("creating a document requires an identity, or it cannot be secured later")
    void documentCreationRequiresAnIdentity() throws Exception {
        HttpResponse<String> anonymous = post("/api/documents", null, "{\"title\":\"Ownerless\"}");

        assertThat(anonymous.statusCode())
            .as("an ownerless document was created and cannot be secured by anyone, ever")
            .isEqualTo(401);
    }

    @Test
    @DisplayName("an unrelated user does not see somebody else's private document in the bin")
    void trashDoesNotLeakForeignDocuments() {
        User anna = newUser("Anna");
        User marek = newUser("Marek");
        UUID document = privateDocument(anna, "Private, in the bin");
        catalog.softDelete(document);

        assertThat(permissions.roleOfDeleted(marek.id(), document))
            .as("the bin became a list of other people's titles")
            .isEmpty();
    }
}
