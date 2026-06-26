package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.app.auth.TokenService;
import dev.collabedit.app.domain.DocumentService;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Documents made of few, large operations (CP28).
 *
 * <p>The resynchronisation path chunked its response by <b>operation count</b> — 300 of them —
 * justified by "an operation is on the order of 60–100 bytes". That is true of typing and false of
 * pasting, which is the one gesture every user performs in the first minute. A pasted chapter is
 * ONE operation of tens of kilobytes.
 *
 * <p>The failure this produces is the one CP8 already found once, and it is silent: the frame
 * exceeds the 64 KiB limit from the transport decision, the client that needs the data most is the one that
 * cannot receive it, and nothing in the log says so. The README carried it as an open item —
 * "the resynchronisation chunk limit is counted in operations, not bytes" — from CP10 until here.
 *
 * <p>This test is written to fail on the old code: twenty operations of 8 000 characters is
 * 160 KB, which the count-based rule would put in a single frame.
 */
class LargePasteIT extends IntegrationTestBase {

    private static final Duration PATIENCE = Duration.ofSeconds(20);

    @LocalServerPort
    int port;

    @Autowired
    TokenService tokens;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DocumentService documents;

    private UUID newDocument(String title) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, title, visibility) VALUES (?, ?, 'link')", id, title);
        return id;
    }

    private String tokenFor(String name) {
        return tokens.issue(
            new TokenService.Principal(UUID.randomUUID().toString(), name, "#4C5FD5"));
    }

    @Test
    @DisplayName("a document of few large operations resynchronises completely")
    void aDocumentOfLargeOperationsCanBeResynchronised() throws Exception {
        UUID document = newDocument("Pasted chapter");

        // What the client produces after CP28 for a ~160 000 character paste: pieces that each
        // fit in a frame on the way IN. The question this test asks is about the way BACK.
        String piece = "x".repeat(8_000);
        int pieces = 20;

        try (TestStompClient author = new TestStompClient(port, tokenFor("Anna"), document, 951)) {
            for (int i = 0; i < pieces; i++) {
                author.insert(i * piece.length(), piece);
                // Paced under the rate limit, which is about the limiter rather than the format.
                Thread.sleep(30);
            }
            author.awaitCondition(() -> author.lastAckSeq() >= pieces, PATIENCE, "the paste");
        }

        assertThat(documents.textOf(document)).hasSize(pieces * piece.length());

        try (TestStompClient reader = new TestStompClient(port, tokenFor("Bartek"), document, 952)) {
            reader.requestSync(0);
            reader.awaitCondition(
                () -> reader.text().length() == pieces * piece.length(), PATIENCE,
                "the whole document arrived — before CP28 the response was one 160 KB frame "
                    + "against a 64 KiB limit, and this client would wait forever with nothing "
                    + "in any log to say why");
        }
    }
}
