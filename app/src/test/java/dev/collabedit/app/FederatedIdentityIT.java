package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.app.auth.LoginCodeStore;
import dev.collabedit.app.auth.TokenService;
import dev.collabedit.app.auth.UserDirectory;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Federated identity: the same person, every time, and a hand-off that does not leak the token.
 *
 * <table>
 *   <caption>Test -&gt; failure mode</caption>
 *   <tr><th>Test</th><th>What it catches</th></tr>
 *   <tr><td>{@code theSameAccountIsTheSamePerson}</td>
 *       <td><b>The defect the whole change exists to fix.</b> Identity used to be a fresh
 *           {@code UUID} per login, so "Anna" today and "Anna" tomorrow owned different documents.
 *           If this ever regresses, every returning user loses everything they made.</td></tr>
 *   <tr><td>{@code twoAccountsAtOneProviderAreTwoPeople}</td>
 *       <td>Over-merging — the mirror failure, and the worse one: two people sharing a row means
 *           one of them can read the other's documents.</td></tr>
 *   <tr><td>{@code theSameSubjectAtTwoProvidersIsTwoPeople}</td>
 *       <td>Keying on the subject alone. Provider identifiers are unique WITHIN an issuer and
 *           nowhere else — GitHub's user 1 and some other provider's user 1 are strangers.</td></tr>
 *   <tr><td>{@code aRenameDoesNotChangeTheColour}</td>
 *       <td>A colour derived from the display name. Somebody renaming themselves at their provider
 *           would change colour mid-document for everyone reading it.</td></tr>
 *   <tr><td>{@code aLoginCodeWorksExactlyOnce}</td>
 *       <td>A replayable hand-off. The code travels in a redirect, so it lands in browser history —
 *           it is safe only because spending it destroys it.</td></tr>
 *   <tr><td>{@code anUnknownCodeIsRefused}</td>
 *       <td>A code exchange that trusts its input, which would turn a guess into a session.</td></tr>
 *   <tr><td>{@code theCodeIsNotStoredInPlaintext}</td>
 *       <td>A credential readable from the table. The same discipline V5 applies to invitations,
 *           and the reason a database read must not yield a way in.</td></tr>
 * </table>
 */
@DisplayName("A provider vouches for a person, and the same person comes back")
class FederatedIdentityIT extends IntegrationTestBase {

    @Autowired
    UserDirectory users;
    @Autowired
    LoginCodeStore codes;
    @Autowired
    JdbcTemplate jdbc;

    private static String subject() {
        return "sub-" + UUID.randomUUID();
    }

    @Test
    @DisplayName("signing in twice with the same provider account is the same person")
    void theSameAccountIsTheSamePerson() {
        String subject = subject();

        TokenService.Principal first =
            users.upsertFederated("google", subject, "Anna", "anna@example.test");
        TokenService.Principal second =
            users.upsertFederated("google", subject, "Anna", "anna@example.test");

        assertThat(second.userId())
            .as("""
                A returning user was given a NEW identifier, which is what login did before it was \
                federated — and it means every document they own, every membership and every \
                comment belongs to somebody who no longer exists.""")
            .isEqualTo(first.userId());
    }

    @Test
    @DisplayName("two accounts at one provider are two people")
    void twoAccountsAtOneProviderAreTwoPeople() {
        TokenService.Principal anna =
            users.upsertFederated("google", subject(), "Anna", "anna@example.test");
        TokenService.Principal marek =
            users.upsertFederated("google", subject(), "Marek", "marek@example.test");

        assertThat(marek.userId())
            .as("two different provider accounts collapsed into one row, so one person can open "
                + "the other's documents")
            .isNotEqualTo(anna.userId());
    }

    @Test
    @DisplayName("the same subject at two providers is two people")
    void theSameSubjectAtTwoProvidersIsTwoPeople() {
        // Provider subjects are unique WITHIN an issuer. GitHub's user "1" and Google's user "1"
        // have nothing to do with each other, and a key that ignores the issuer merges them.
        String shared = subject();

        TokenService.Principal viaGoogle =
            users.upsertFederated("google", shared, "Anna", "anna@example.test");
        TokenService.Principal viaGithub =
            users.upsertFederated("github", shared, "Someone Else", "else@example.test");

        assertThat(viaGithub.userId())
            .as("the identity is keyed on the subject alone, so an account at one provider "
                + "unlocks somebody else's account at another")
            .isNotEqualTo(viaGoogle.userId());
    }

    @Test
    @DisplayName("a rename at the provider updates the name and keeps the colour")
    void aRenameDoesNotChangeTheColour() {
        String subject = subject();
        TokenService.Principal before =
            users.upsertFederated("google", subject, "Anna", "anna@example.test");

        TokenService.Principal after =
            users.upsertFederated("google", subject, "Anna Nowak", "anna@example.test");

        assertThat(after.displayName())
            .as("the provider is the authority on the name and it was not refreshed")
            .isEqualTo("Anna Nowak");
        assertThat(after.color())
            .as("""
                The colour moved with the name. It is the only continuity a reader of the document \
                has, so somebody renaming themselves would change colour mid-sentence for everyone \
                else — which is why it is derived from the identifier and not the name.""")
            .isEqualTo(before.color());
    }

    @Test
    @DisplayName("a login code works exactly once")
    void aLoginCodeWorksExactlyOnce() {
        TokenService.Principal anna =
            users.upsertFederated("google", subject(), "Anna", "anna@example.test");

        String code = codes.issue(anna.userId());

        assertThat(codes.redeem(code)).contains(anna.userId());
        assertThat(codes.redeem(code))
            .as("""
                The code was spendable twice. It travels in a redirect, so it is in browser \
                history and in the Referer of whatever the page loads next — the only thing making \
                that acceptable is that spending it destroys it.""")
            .isEmpty();
    }

    @Test
    @DisplayName("a code nobody issued is refused")
    void anUnknownCodeIsRefused() {
        assertThat(codes.redeem("not-a-code-anybody-issued")).isEmpty();
        assertThat(codes.redeem("")).isEmpty();
        assertThat(codes.redeem(null)).isEmpty();
    }

    @Test
    @DisplayName("the code is stored hashed, so reading the table does not yield one")
    void theCodeIsNotStoredInPlaintext() {
        TokenService.Principal anna =
            users.upsertFederated("google", subject(), "Anna", "anna@example.test");

        String code = codes.issue(anna.userId());

        Integer stored = jdbc.queryForObject(
            "SELECT COUNT(*) FROM login_codes WHERE code_hash = ?", Integer.class, code);
        assertThat(stored)
            .as("""
                The plaintext code is in the table. A row that IS a credential means a backup, a \
                slow-query log or a support query hands out a session — the same reason invitation \
                tokens are hashed.""")
            .isZero();
    }
}
