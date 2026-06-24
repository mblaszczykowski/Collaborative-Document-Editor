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

/**
 * Access control (CP12) — <b>the most important set of tests in this checkpoint</b>.
 *
 * <p>Before CP12 there was no authorization: the token was checked once, at {@code CONNECT}, and
 * the control ended there. With one shared document that was a coherent simplification. Since CP11
 * the document identifier is part of an address the client supplies, so the same simplification
 * becomes a vulnerability — and by <b>two routes at once</b>.
 *
 * <table>
 *   <caption>Test -> failure mode</caption>
 *   <tr><th>Test</th><th>What it catches</th></tr>
 *   <tr><td>{@code restCannotReadForeignPrivateDocument}</td>
 *       <td>IDOR over REST. {@code GET /api/documents/{id}} returns the FULL CONTENT — without an
 *           access check the whole WebSocket authorization is decoration, because the same data
 *           leaves through one request.</td></tr>
 *   <tr><td>{@code websocketCannotSubscribeToForeignPrivateDocument}</td>
 *       <td>IDOR over WebSocket — watching somebody else's document LIVE, character by character.</td></tr>
 *   <tr><td>{@code viewerCannotWriteText}</td>
 *       <td>Authorization per document instead of per operation. A read-only role that can change
 *           the content anyway is not a role.</td></tr>
 *   <tr><td>{@code revokingAccessTakesEffect}</td>
 *       <td>Permissions remembered forever. A revocation that does not work is worse than none at
 *           all — because somebody is relying on it.</td></tr>
 *   <tr><td>{@code linkVisibilityKeepsSharedDocumentsWorking}</td>
 *       <td>A migration taking away access to documents that used to work. A protection that breaks
 *           existing data will not reach production.</td></tr>
 *   <tr><td>{@code deletedDocumentIsInvisibleEvenToOwner}</td>
 *       <td>The bin as a way around permissions, or as a source of a deleted document's content.</td></tr>
 * </table>
 */
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

    /** A private document belonging to the given user. */
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

    // ------------------------------------------------------------------

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
            .as("a request with no token received a private document's content")
            .isEqualTo(404);
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
            // The test client does not fetch the initial state itself — it builds it from
            // broadcasts. A resynchronisation request is therefore necessary here, and it also
            // checks that this path IS allowed for a read-only role: an observer is meant to see
            // the document, just not change it.
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

            // Formatting goes over the SAME channel as text and is just as much a change to the
            // document. A separate test, because in the interface it has its own entry point — a
            // keyboard shortcut that `EditorState.readOnly` does not block.
            viewer.markReturningOp(0, 5, "bold", null);
            Thread.sleep(500);
        }

        try (TestStompClient check = new TestStompClient(port, anna.token(), document, 708)) {
            check.requestSync(0);
            check.awaitCondition(() -> check.text().equals("original"), PATIENCE, "state fetched");
            assertThat(check.markCount())
                .as("a reader applied formatting — a read-only role changed the document")
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

        // The invalidation is part of revoke() — without it the change would only take effect
        // once the cache entry expired, that is, after long enough for the user to conclude that
        // revoking access does not work.
        assertThat(permissions.canRead(marek.id(), document))
            .as("revoking access did nothing — the permission was remembered")
            .isFalse();
        assertThat(get("/api/documents/" + document, marek.token()).statusCode()).isEqualTo(404);
    }

    /**
     * The half of revocation that did not exist, named by {@code PLAN-v2} and never written.
     *
     * <h2>What was wrong</h2>
     * {@code DocumentAccessInterceptor} was registered on the INBOUND channel only, and nothing
     * looked at what went OUT. So after a revocation the user's writes were refused and their next
     * REST request answered 404 — while <b>every broadcast kept arriving in their browser</b>: the
     * text other people were typing, character by character, plus the presence of everyone in the
     * room, indefinitely.
     *
     * <p>It was invisible from the server. The refusals in the log looked exactly as they should,
     * because it is the WRITES that were being refused, and {@code revokingAccessTakesEffect} above
     * passes on the old code: it checks the permission and the REST route, which are the two halves
     * that worked. The README claimed revocation took effect within the cache's five seconds, and
     * for reading it never took effect at all.
     *
     * <p>This test holds a live session ACROSS the revocation, which is the only arrangement that
     * can tell the difference — a client that reconnects afterwards is refused at SUBSCRIBE by the
     * inbound half and proves nothing about the outbound one.
     */
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
            // Revoked through the ENDPOINT rather than through PermissionService, because the
            // notice below is published by the controller. Calling the service directly tests the
            // half of the mechanism that has no user in front of it — and it is what let the
            // missing notice go unnoticed here in the first place.
            assertThat(delete("/api/documents/" + document + "/access/members/" + marek.id(),
                anna.token()).statusCode())
                .as("the owner revokes through the endpoint the interface actually calls")
                .isEqualTo(204);

            long before = owner.lastAckSeq();
            owner.insert(owner.text().length(), "AFTER");
            // Waited for POSITIVELY, not slept through. The assertion below is a negative one, and
            // this codebase has already found once (DocumentCollaborationIT) that a negative
            // assertion guarded by a fixed sleep passes whenever the machine is slower than the
            // sleep. The acknowledgement advancing is proof that the operation has been merged,
            // broadcast and written — that is, that the frame the guest must not have received has
            // already gone out to everybody who was entitled to it.
            owner.awaitCondition(() -> owner.lastAckSeq() > before, PATIENCE,
                "the second write is acknowledged, so its broadcast has already happened");
            // A short additional margin only for the delivery hop to the guest's own socket, which
            // has no signal of its own to wait on — the whole point is that nothing arrives.
            Thread.sleep(300);

            assertThat(guest.text())
                .as("""
                    A revoked user kept receiving the document's broadcasts on a subscription that \
                    was established before the revocation. Their writes are refused and their REST \
                    requests answer 404, so from the server's own logs the revocation looks \
                    complete — while they are watching the document being typed.""")
                .doesNotContain("AFTER");
            assertThat(guest.text())
                .as("what they already had is not the question here; what arrives afterwards is")
                .contains("BEFORE");

            // THE OTHER HALF, and the one the first version of the filter broke. A revoked client
            // must stop receiving the document's CONTENT and must still receive the notice that
            // its access changed — that frame is the only thing that makes the client re-ask for
            // its role, and without it the tab simply goes quiet: still showing the document, and
            // still letting somebody type into a replica whose operations are being refused.
            //
            // Safe to deliver because Messages.AccessChanged is EMPTY; CP21 removed its fields so
            // that this broadcast cannot say anything GET /access would refuse.
            guest.awaitCondition(() -> guest.accessChangeCount() > noticesBefore, PATIENCE,
                "the revoked client is never told its access changed, so it has no way to find "
                    + "out — the one frame whose purpose is to say so was the one being dropped");
        }

    }

    @Test
    @DisplayName("a document with 'link' visibility stays available to every logged-in user")
    void linkVisibilityKeepsSharedDocumentsWorking() throws Exception {
        User anna = newUser("Anna");
        User anybody = newUser("Anybody");

        // The default visibility — exactly what documents created before CP12 have, and all those
        // created without an explicit settings change.
        UUID document = UUID.randomUUID();
        catalog.create(document, "Shared by link", null);
        permissions.setOwner(document, anna.id());

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

        // ...but the bin has to show it, otherwise it cannot be restored.
        assertThat(permissions.roleOfDeleted(anna.id(), document))
            .as("the owner cannot see their own document in the bin — it cannot be restored")
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

        // The role travels with the initial state for the same reason a rate-limit refusal is
        // loud: enforcing on the server WITHOUT telling the client turns a refusal into silent
        // divergence. A reader who does not know they are a reader types, sees their own text, and
        // the server rejects it — and their local replica never agrees with the rest again,
        // without a single message.
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
    @DisplayName("history is not a third route into somebody else's document — for reading or writing")
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

        // CP12 closed TWO routes to the content — the WebSocket and GET /documents/{id} — and
        // missed a third, even though the v2 plan pointed at it explicitly. History handed out the
        // full content of any document, so the CP12 fix could be bypassed with one request.
        assertThat(get("/api/documents/" + document + "/history", outsider.token()).statusCode())
            .as("the timeline of somebody else's private document is visible")
            .isEqualTo(404);
        assertThat(get("/api/documents/" + document + "/history/at?seq=1", outsider.token()).body())
            .as("history hands out the content of somebody else's private document")
            .doesNotContain("secret");

        // THE IMPORTANT ONE: restoring changes the content, so it requires WRITE, not read.
        // Without this a reader had the only route in the project to change somebody else's text —
        // and not by a character, but by rewinding the whole document.
        assertThat(post("/api/documents/" + document + "/history/restore",
                reader.token(), "{\"throughServerSeq\":1}").statusCode())
            .as("a reader restored a version — a read-only role can change the content")
            .isEqualTo(404);
        assertThat(post("/api/documents/" + document + "/history/restore",
                outsider.token(), "{\"throughServerSeq\":1}").statusCode())
            .isEqualTo(404);

        // ...and the owner is allowed, otherwise the protection would break the feature.
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
            .as("workspace inheritance does not work — the team's document is invisible")
            .contains(Role.EDITOR);

        // The crux: the override has to work DOWNWARDS, not only upwards. If the stronger role
        // won, there would be no way to restrict somebody's access to ONE document without
        // ejecting them from the whole workspace — that is, inheritance would take control away
        // instead of giving it.
        permissions.grant(document, teamMember.id(), Role.VIEWER);

        assertThat(permissions.roleOf(teamMember.id(), document))
            .as("the per-document role did not override the inherited one — restricting access does not work")
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

        // Another user's identifier is NOT a secret — it is visible in every presence frame of a
        // shared document. Authorization resting on somebody not knowing it is not authorization;
        // the same bug as the IDOR closed in CP12, on a different entity.
        assertThat(get("/api/workspaces?owner=" + anna.id(), snooper.token()).body())
            .as("a request parameter decides whose workspaces the caller sees")
            .doesNotContain("Anna's private team");

        assertThat(get("/api/workspaces?owner=" + anna.id(), anna.token()).body())
            .as("the owner stopped seeing their own workspaces")
            .contains("Anna's private team");

        // Creation too: without this it was possible to insert an entry onto somebody else's list.
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

        // A draft is created from ONE person's unsynchronised work that nobody else has ever seen.
        // The default "link" visibility would hand it to every logged-in user at exactly the moment
        // the user is worried about losing it.
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
    @DisplayName("a recovered draft cannot be created without an identity — the CP16 finding")
    void recoveredDraftCannotBeCreatedAnonymously() throws Exception {
        // The test above covers the path where a valid token is present. This one covers the
        // path that was actually broken, and it is not the exotic one.
        //
        // Owner and visibility used to be set only inside `if (caller != null)`. With no token
        // — or, far more importantly, an EXPIRED one — both calls were skipped: no owner, and
        // `visibility` falling back to the schema default of 'link', which PermissionService
        // rule 4 reads as EDITOR for every logged-in user. The exact inverse of what the test
        // above asserts, reachable by leaving out one header.
        //
        // Why this is the typical path rather than the edge: a token lives 12 hours, and this
        // endpoint exists ONLY for clients that were offline longer than the 7-day GC window.
        // The population it was written for is the population whose token has certainly expired.
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
        // Same class of bug as above, found in the same pass: `POST /api/documents` also set
        // the owner only when one happened to be present. A document created without an owner
        // can never have its visibility changed or access granted — it is unsecurable for the
        // rest of its life, and nothing anywhere reports that.
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
