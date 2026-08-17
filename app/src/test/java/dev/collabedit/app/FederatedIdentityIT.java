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
                federated - and it means every document they own, every membership and every \
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
                else - which is why it is derived from the identifier and not the name.""")
            .isEqualTo(before.color());
    }

    @Test
    @DisplayName("a very long name is shortened without splitting a character")
    void aLongNameIsCutOnACharacterBoundary() {
        String name = "a".repeat(63) + "😀";

        TokenService.Principal stored =
            users.upsertFederated("google", subject(), name, "long@example.test");

        assertThat(stored.displayName().length())
            .as("the name was stored unbounded; it travels in every presence broadcast")
            .isLessThanOrEqualTo(64);
        assertThat(stored.displayName().chars().noneMatch(unit -> Character.isSurrogate((char) unit)))
            .as("""
                The cut landed between the two halves of a surrogate pair, leaving a lone surrogate \
                - not a character, and not encodable as UTF-8. Emoji in a provider's display name \
                is ordinary, so this is an ordinary name rather than a hostile one.""")
            .isTrue();
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
                history and in the Referer of whatever the page loads next - the only thing making \
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
                slow-query log or a support query hands out a session - the same reason invitation \
                tokens are hashed.""")
            .isZero();
    }
}
