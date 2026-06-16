package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.collabedit.app.auth.PermissionService;
import dev.collabedit.app.auth.Role;
import dev.collabedit.app.auth.TokenService;
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
 * Sharing a document with a specific person (CP21).
 *
 * <p>These tests exist because the role model was complete, enforced and <b>unreachable</b>:
 * {@code grant} and {@code revoke} were called from {@code PermissionIT} and from no production
 * code at all. {@code PermissionIT} therefore proves that the rule is right; this class proves
 * there is a way to invoke it, which is a different claim and was the false one.
 *
 * <table>
 *   <caption>Test → failure mode</caption>
 *   <tr><th>Test</th><th>What it catches</th></tr>
 *   <tr><td>{@code freshDocumentIsPrivate}</td>
 *       <td>The schema default {@code link} coming back. It means every document created through
 *           the interface is editable by anybody who logged in — four documented roles collapsing
 *           into two the product can actually produce.</td></tr>
 *   <tr><td>{@code grantedRoleIsTheRoleAsked}</td>
 *       <td>A grant that lands as something else. Off-by-one in a role ordering is silent and
 *           permanent.</td></tr>
 *   <tr><td>{@code unknownRoleNameIsRefused}</td>
 *       <td>{@code Role.parse} falling back to VIEWER on the WRITE path: a typo would report
 *           success and grant a different role than the one named.</td></tr>
 *   <tr><td>{@code ownershipCannotBeGranted}, {@code ownerCannotRevokeThemselves}</td>
 *       <td>The two ways an access API produces a document nobody can administer.</td></tr>
 *   <tr><td>{@code memberListRequiresManageAccess}</td>
 *       <td>"Who else has access" leaking to everyone who may read the text.</td></tr>
 *   <tr><td>{@code inviteGrantsExactlyTheRoleItCarries}</td>
 *       <td>The invite being treated as "let them in" rather than as "let them in AS THIS". A
 *           bearer credential that grants more than it says is the worst kind.</td></tr>
 *   <tr><td>{@code revokedInviteStopsWorking}, {@code expiredInviteStopsWorking}</td>
 *       <td>An invitation that cannot be taken back — the reason invites are a table and not a
 *           signed token.</td></tr>
 *   <tr><td>{@code redeemingNeverDemotes}</td>
 *       <td>The owner testing their own share link and losing their document to it.</td></tr>
 * </table>
 */
class AccessIT extends IntegrationTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    int port;

    @Autowired
    TokenService tokens;
    @Autowired
    PermissionService permissions;
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

    /** Creates a document the way the product does — through REST, as a logged-in caller. */
    private UUID createDocument(User owner, String title) throws Exception {
        HttpResponse<String> response = send("POST", "/api/documents", owner.token(),
            "{\"title\":\"" + title + "\"}");
        assertThat(response.statusCode()).isEqualTo(200);
        return UUID.fromString(JSON.readTree(response.body()).get("id").asText());
    }

    private HttpResponse<String> send(String method, String path, String token, String body)
            throws Exception {
        HttpRequest.BodyPublisher payload = body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .method(method, payload);
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String mintInvite(User owner, UUID document, String role) throws Exception {
        HttpResponse<String> response = send("POST",
            "/api/documents/" + document + "/access/invites", owner.token(),
            "{\"role\":\"" + role + "\"}");
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body()).get("token").asText();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("a freshly created document is private, so a stranger sees nothing")
    void freshDocumentIsPrivate() throws Exception {
        User anna = newUser("Anna");
        User marek = newUser("Marek");
        UUID document = createDocument(anna, "Notes");

        assertThat(send("GET", "/api/documents/" + document, anna.token(), null).statusCode())
            .as("the owner must be able to open their own document")
            .isEqualTo(200);

        assertThat(send("GET", "/api/documents/" + document, marek.token(), null).statusCode())
            .as("until CP21 this was 200 with full edit rights, because the column defaults "
                + "to 'link' and 'link' means EDITOR for every logged-in user")
            .isEqualTo(404);
    }

    @Test
    @DisplayName("granting a role gives exactly that role")
    void grantedRoleIsTheRoleAsked() throws Exception {
        User anna = newUser("Anna");
        User marek = newUser("Marek");
        UUID document = createDocument(anna, "Notes");

        assertThat(send("PUT", "/api/documents/" + document + "/access/members/" + marek.id(),
            anna.token(), "{\"role\":\"commenter\"}").statusCode()).isEqualTo(204);

        HttpResponse<String> mine =
            send("GET", "/api/documents/" + document + "/access/me", marek.token(), null);
        assertThat(mine.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(mine.body()).get("role").asText()).isEqualTo("commenter");
        assertThat(permissions.roleOf(marek.id(), document)).contains(Role.COMMENTER);
    }

    @Test
    @DisplayName("revoking access takes it away")
    void revokingRemovesAccess() throws Exception {
        User anna = newUser("Anna");
        User marek = newUser("Marek");
        UUID document = createDocument(anna, "Notes");

        send("PUT", "/api/documents/" + document + "/access/members/" + marek.id(),
            anna.token(), "{\"role\":\"editor\"}");
        assertThat(send("GET", "/api/documents/" + document, marek.token(), null).statusCode())
            .isEqualTo(200);

        assertThat(send("DELETE", "/api/documents/" + document + "/access/members/" + marek.id(),
            anna.token(), null).statusCode()).isEqualTo(204);

        assertThat(send("GET", "/api/documents/" + document, marek.token(), null).statusCode())
            .as("revocation invalidates the cache on this instance, so it is immediate here")
            .isEqualTo(404);
    }

    @Test
    @DisplayName("an editor cannot grant access, and cannot even see who has it")
    void memberListRequiresManageAccess() throws Exception {
        User anna = newUser("Anna");
        User marek = newUser("Marek");
        User obcy = newUser("Obcy");
        UUID document = createDocument(anna, "Notes");
        send("PUT", "/api/documents/" + document + "/access/members/" + marek.id(),
            anna.token(), "{\"role\":\"editor\"}");

        assertThat(send("GET", "/api/documents/" + document + "/access", marek.token(), null)
            .statusCode())
            .as("who has access is a different question from what the document says")
            .isEqualTo(404);

        assertThat(send("PUT", "/api/documents/" + document + "/access/members/" + obcy.id(),
            marek.token(), "{\"role\":\"editor\"}").statusCode())
            .as("an editor granting access would make every role equal to owner")
            .isEqualTo(404);
    }

    @Test
    @DisplayName("ownership is not something the access API hands out")
    void ownershipCannotBeGranted() throws Exception {
        User anna = newUser("Anna");
        User marek = newUser("Marek");
        UUID document = createDocument(anna, "Notes");

        assertThat(send("PUT", "/api/documents/" + document + "/access/members/" + marek.id(),
            anna.token(), "{\"role\":\"owner\"}").statusCode()).isEqualTo(400);
        assertThat(send("POST", "/api/documents/" + document + "/access/invites",
            anna.token(), "{\"role\":\"owner\"}").statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("a misspelled role is refused instead of quietly becoming viewer")
    void unknownRoleNameIsRefused() throws Exception {
        User anna = newUser("Anna");
        User marek = newUser("Marek");
        UUID document = createDocument(anna, "Notes");

        assertThat(send("PUT", "/api/documents/" + document + "/access/members/" + marek.id(),
            anna.token(), "{\"role\":\"editr\"}").statusCode()).isEqualTo(400);
        assertThat(permissions.roleOf(marek.id(), document))
            .as("Role.parse would have made this a viewer and reported success")
            .isEmpty();
    }

    @Test
    @DisplayName("the owner cannot revoke themselves out of their own document")
    void ownerCannotRevokeThemselves() throws Exception {
        User anna = newUser("Anna");
        UUID document = createDocument(anna, "Notes");

        assertThat(send("DELETE", "/api/documents/" + document + "/access/members/" + anna.id(),
            anna.token(), null).statusCode()).isEqualTo(409);
        assertThat(permissions.roleOf(anna.id(), document)).contains(Role.OWNER);
    }

    @Test
    @DisplayName("an invitation grants exactly the role it carries")
    void inviteGrantsExactlyTheRoleItCarries() throws Exception {
        User anna = newUser("Anna");
        User marek = newUser("Marek");
        UUID document = createDocument(anna, "Notes");
        String token = mintInvite(anna, document, "commenter");

        HttpResponse<String> redeemed = send("POST", "/api/invites/redeem", marek.token(),
            "{\"token\":\"" + token + "\"}");
        assertThat(redeemed.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(redeemed.body()).get("documentId").asText())
            .as("the document comes from the invite, never from the request")
            .isEqualTo(document.toString());
        assertThat(permissions.roleOf(marek.id(), document)).contains(Role.COMMENTER);
    }

    @Test
    @DisplayName("a revoked invitation stops working")
    void revokedInviteStopsWorking() throws Exception {
        User anna = newUser("Anna");
        User marek = newUser("Marek");
        UUID document = createDocument(anna, "Notes");
        String token = mintInvite(anna, document, "editor");

        String inviteId = JSON.readTree(
            send("GET", "/api/documents/" + document + "/access", anna.token(), null).body())
            .get("invites").get(0).get("id").asText();
        assertThat(send("DELETE",
            "/api/documents/" + document + "/access/invites/" + inviteId, anna.token(), null)
            .statusCode()).isEqualTo(204);

        assertThat(send("POST", "/api/invites/redeem", marek.token(),
            "{\"token\":\"" + token + "\"}").statusCode())
            .as("revocation is the whole reason invites are rows rather than signatures")
            .isEqualTo(404);
        assertThat(permissions.roleOf(marek.id(), document)).isEmpty();
    }

    @Test
    @DisplayName("an expired invitation stops working, with no sweep having to run")
    void expiredInviteStopsWorking() throws Exception {
        User anna = newUser("Anna");
        User marek = newUser("Marek");
        UUID document = createDocument(anna, "Notes");
        String token = mintInvite(anna, document, "editor");

        // Expiry is checked at redemption, not by a job. Moving the row into the past is
        // therefore enough to test it — and the fact that it IS enough is the property:
        // nothing has to run for the invitation to stop working.
        jdbc.update("UPDATE document_invites SET expires_at = now() - interval '1 hour' "
            + "WHERE doc_id = ?", document);

        assertThat(send("POST", "/api/invites/redeem", marek.token(),
            "{\"token\":\"" + token + "\"}").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("redeeming your own invitation does not demote you")
    void redeemingNeverDemotes() throws Exception {
        User anna = newUser("Anna");
        UUID document = createDocument(anna, "Notes");
        String token = mintInvite(anna, document, "viewer");

        assertThat(send("POST", "/api/invites/redeem", anna.token(),
            "{\"token\":\"" + token + "\"}").statusCode()).isEqualTo(200);
        assertThat(permissions.roleOf(anna.id(), document))
            .as("the owner checking their own share link must not lose the document to it")
            .contains(Role.OWNER);
    }

    @Test
    @DisplayName("an invitation cannot be redeemed by somebody who is not logged in")
    void redeemNeedsAnIdentity() throws Exception {
        User anna = newUser("Anna");
        UUID document = createDocument(anna, "Notes");
        String token = mintInvite(anna, document, "editor");

        assertThat(send("POST", "/api/invites/redeem", null,
            "{\"token\":\"" + token + "\"}").statusCode())
            .as("a grant needs somebody to grant to")
            .isEqualTo(401);
    }

    @Test
    @DisplayName("switching visibility to link restores the old open behaviour, deliberately")
    void visibilitySwitchIsTheEscapeHatch() throws Exception {
        User anna = newUser("Anna");
        User marek = newUser("Marek");
        UUID document = createDocument(anna, "Notes");

        assertThat(send("GET", "/api/documents/" + document, marek.token(), null).statusCode())
            .isEqualTo(404);

        assertThat(send("PUT", "/api/documents/" + document + "/access/visibility",
            anna.token(), "{\"visibility\":\"link\"}").statusCode()).isEqualTo(204);

        assertThat(send("GET", "/api/documents/" + document, marek.token(), null).statusCode())
            .as("the open document did not disappear as an option; it stopped being the default")
            .isEqualTo(200);

        assertThat(send("PUT", "/api/documents/" + document + "/access/visibility",
            anna.token(), "{\"visibility\":\"everyone\"}").statusCode())
            .as("an unknown visibility is a client error, not a silent fallback")
            .isEqualTo(400);
    }
}
