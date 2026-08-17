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

        String piece = "x".repeat(8_000);
        int pieces = 20;

        try (TestStompClient author = new TestStompClient(port, tokenFor("Anna"), document, 951)) {
            for (int i = 0; i < pieces; i++) {
                author.insert(i * piece.length(), piece);
                Thread.sleep(30);
            }
            author.awaitCondition(() -> author.lastAckSeq() >= pieces, PATIENCE, "the paste");
        }

        assertThat(documents.textOf(document)).hasSize(pieces * piece.length());

        try (TestStompClient reader = new TestStompClient(port, tokenFor("Bartek"), document, 952)) {
            reader.requestSync(0);
            reader.awaitCondition(
                () -> reader.text().length() == pieces * piece.length(), PATIENCE,
                "the whole document arrived - the response used to be one 160 KB frame "
                    + "against a 64 KiB limit, and this client would wait forever with nothing "
                    + "in any log to say why");
        }
    }
}
