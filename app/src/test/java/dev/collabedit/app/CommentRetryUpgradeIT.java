package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.collabedit.app.comments.CommentService;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class CommentRetryUpgradeIT extends IntegrationTestBase {
    @Autowired JdbcTemplate applicationJdbc;

    @Test
    void populatedLegacyCommentsKeepOriginalRetryBodyWhenRepliesShareItsTimestamp() throws Exception {
        String schema = "comment_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema).defaultSchema(schema).target("13").load().migrate();
        try (var connection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.setSchema(schema);
            JdbcTemplate legacy = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            String author = UUID.randomUUID().toString();
            UUID document = UUID.randomUUID(), thread = UUID.randomUUID();
            String key = "legacy-request";
            Timestamp timestamp = Timestamp.valueOf("2026-01-01 12:00:00");
            legacy.update("INSERT INTO users (id, display_name, color) VALUES (?, 'Legacy reviewer', '#123456')", author);
            legacy.update("INSERT INTO documents (id, title, visibility) VALUES (?, 'Legacy comments', 'private')", document);
            legacy.update("INSERT INTO comment_threads (id, doc_id, created_by, quoted_text, client_id, created_at) "
                + "VALUES (?, ?, ?, 'quote', ?, ?)", thread, document, author, key, timestamp);
            // PostgreSQL now() is transaction-scoped. UUID ordering cannot identify the first message.
            legacy.update("INSERT INTO comment_messages (id, thread_id, author_id, body, client_id, created_at) "
                + "VALUES (?, ?, ?, 'reply body', 'reply-request', ?)",
                UUID.fromString("00000000-0000-0000-0000-000000000001"), thread, author, timestamp);
            legacy.update("INSERT INTO comment_messages (id, thread_id, author_id, body, client_id, created_at) "
                + "VALUES (?, ?, ?, 'original body', ?, ?)",
                UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"), thread, author, key + ":first", timestamp);

            UUID unkeyed = UUID.randomUUID();
            legacy.update("INSERT INTO comment_threads (id, doc_id, created_by, quoted_text) VALUES (?, ?, ?, '')",
                unkeyed, document, author);
            legacy.update("INSERT INTO comment_messages (id, thread_id, author_id, body, created_at) "
                + "VALUES (?, ?, ?, 'unkeyed original', ?)", UUID.randomUUID(), unkeyed, author, timestamp);
            legacy.update("INSERT INTO comment_messages (id, thread_id, author_id, body, created_at) "
                + "VALUES (?, ?, ?, 'unkeyed later reply', ?)", UUID.randomUUID(), unkeyed, author,
                Timestamp.valueOf("2026-01-01 12:00:01"));

            Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).target("14").load().migrate();
            assertThat(legacy.queryForObject("SELECT initial_body FROM comment_threads WHERE id = ?", String.class, thread))
                .isEqualTo("original body");
            assertThat(legacy.queryForObject("SELECT initial_body FROM comment_threads WHERE id = ?", String.class, unkeyed))
                .isEqualTo("unkeyed original");
            CommentService comments = new CommentService(legacy);
            assertThat(comments.openThread(document, author, "quote", "original body", key)).isEqualTo(thread);
            assertThatThrownBy(() -> comments.openThread(document, author, "quote", "reply body", key))
                .isInstanceOf(IllegalStateException.class);
            assertThat(legacy.queryForObject("SELECT count(*) FROM comment_threads", Integer.class)).isEqualTo(2);
        } finally {
            // Only the isolated schema created by this test is removed; application data is untouched.
            applicationJdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
}
