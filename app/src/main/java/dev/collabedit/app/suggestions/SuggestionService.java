package dev.collabedit.app.suggestions;

import dev.collabedit.app.domain.DocumentRegistry;
import dev.collabedit.app.domain.DocumentService;
import dev.collabedit.crdt.Block;
import dev.collabedit.crdt.Id;
import dev.collabedit.crdt.MarkSet;
import dev.collabedit.crdt.MarkTypes;
import dev.collabedit.crdt.Op;
import dev.collabedit.crdt.RgaDocument;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SuggestionService {
    private static final Logger log = LoggerFactory.getLogger(SuggestionService.class);

    private final JdbcTemplate jdbc;
    private final DocumentRegistry registry;
    private final DocumentService documents;

    public SuggestionService(JdbcTemplate jdbc, DocumentRegistry registry,
                             DocumentService documents) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.documents = documents;
    }

    public enum Kind { INSERT, DELETE }

    public record Suggestion(UUID id, UUID documentId, String authorId, String authorName,
                             String authorColor, Kind kind, String quotedText, Instant createdAt,
                             boolean anchored, Range range) {
    }

    public record Range(int from, int to) {
    }

    public Optional<UUID> proposeInsertion(UUID documentId, String authorId, Id afterId,
                                           String text) {
        if (text == null || text.isEmpty()) {
            return Optional.empty();
        }
        UUID suggestionId = UUID.randomUUID();

        record(suggestionId, documentId, authorId, Kind.INSERT, text);

        List<Op> generated = documents.generateAndSubmit(documentId, document -> {
            Integer anchor = afterId == null ? -1 : visibleIndexOf(document, afterId);
            if (anchor == null) {
                return null;
            }
            int position = anchor + 1;
            List<Op> ops = new ArrayList<>();
            ops.add(document.insert(position, text));
            ops.add(document.addMark(position, position + text.length(),
                MarkTypes.SUGGEST_INSERT, suggestionId.toString()));
            return ops;
        }, null);

        if (generated == null) {
            return Optional.empty();
        }
        log.info("Suggestion {} (insert) proposed in document {} by {}", suggestionId, documentId,
            authorId);
        return Optional.of(suggestionId);
    }

    public Optional<UUID> proposeDeletion(UUID documentId, String authorId, Id fromId, Id toId) {
        UUID suggestionId = UUID.randomUUID();

        String quoted = registry.get(documentId).withLock(document -> {
            Integer from = visibleIndexOf(document, fromId);
            Integer to = visibleIndexOf(document, toId);
            if (from == null || to == null || to < from) {
                return null;
            }
            return document.text().substring(from, to + 1);
        });
        if (quoted == null) {
            return Optional.empty();
        }
        record(suggestionId, documentId, authorId, Kind.DELETE, quoted);

        List<Op> generated = documents.generateAndSubmit(documentId, document -> {
            Integer from = visibleIndexOf(document, fromId);
            Integer to = visibleIndexOf(document, toId);
            if (from == null || to == null || to < from) {
                return null;
            }
            return List.of(document.addMark(from, to + 1,
                MarkTypes.SUGGEST_DELETE, suggestionId.toString()));
        }, null);

        if (generated == null) {
            return Optional.empty();
        }
        log.info("Suggestion {} (delete) proposed in document {} by {}", suggestionId, documentId,
            authorId);
        return Optional.of(suggestionId);
    }

    public Outcome resolve(UUID documentId, UUID suggestionId, String userId, boolean accept) {
        Kind kind = kindOf(documentId, suggestionId);
        if (kind == null) {
            return existsAtAll(documentId, suggestionId) ? Outcome.ALREADY_RESOLVED : Outcome.UNKNOWN;
        }

        String markType = kind == Kind.INSERT
            ? MarkTypes.SUGGEST_INSERT
            : MarkTypes.SUGGEST_DELETE;
        boolean textStays = accept == (kind == Kind.INSERT);

        int updated = jdbc.update("""
            UPDATE suggestions
               SET resolved_at = now(), resolved_by = ?, resolution = ?
             WHERE id = ? AND doc_id = ? AND resolved_at IS NULL
            """, userId, accept ? "accepted" : "rejected", suggestionId, documentId);
        if (updated == 0) {
            return Outcome.ALREADY_RESOLVED;
        }

        try {
            documents.generateAndSubmit(documentId, document -> {
                Range range = rangeOf(document, markType, suggestionId.toString());
                if (range == null) {
                    return List.<Op>of();
                }
                List<Op> ops = new ArrayList<>();
                if (textStays) {
                    ops.add(document.removeMark(range.from(), range.to(), markType));
                } else {
                    ops.add(document.delete(range.from(), range.to() - range.from()));
                }
                return ops;
            }, null);
        } catch (RuntimeException e) {
            reopen(suggestionId, documentId);
            throw e;
        }

        log.info("Suggestion {} {} by {}", suggestionId, accept ? "accepted" : "rejected", userId);
        return Outcome.RESOLVED;
    }

    public enum Outcome {
        RESOLVED, ALREADY_RESOLVED, UNKNOWN
    }

    private void reopen(UUID suggestionId, UUID documentId) {
        try {
            jdbc.update("""
                UPDATE suggestions
                   SET resolved_at = NULL, resolved_by = NULL, resolution = NULL
                 WHERE id = ? AND doc_id = ? AND resolved_at IS NOT NULL
                """, suggestionId, documentId);
        } catch (RuntimeException e) {
            log.error("Suggestion {} was settled but could not be applied OR reopened; its mark is "
                + "still in document {} and the proposal is hidden from the panel",
                suggestionId, documentId, e);
        }
    }

    private boolean existsAtAll(UUID documentId, UUID suggestionId) {
        return !jdbc.queryForList("SELECT 1 FROM suggestions WHERE id = ? AND doc_id = ?",
            Integer.class, suggestionId, documentId).isEmpty();
    }

    public List<Suggestion> openFor(UUID documentId) {
        List<Row> rows = jdbc.query("""
            SELECT s.id, s.author_id, s.kind, s.quoted_text, s.created_at,
                   COALESCE(u.display_name, 'Unknown') AS display_name,
                   COALESCE(u.color, '#999999')        AS color
              FROM suggestions s
              LEFT JOIN users u ON u.id = s.author_id
             WHERE s.doc_id = ? AND s.resolved_at IS NULL
             ORDER BY s.created_at
            """,
            (rs, rowNum) -> new Row(
                (UUID) rs.getObject("id"),
                rs.getString("author_id"),
                rs.getString("display_name"),
                rs.getString("color"),
                Kind.valueOf(rs.getString("kind").toUpperCase()),
                rs.getString("quoted_text"),
                rs.getTimestamp("created_at").toInstant()),
            documentId);

        if (rows.isEmpty()) {
            return List.of();
        }

        return registry.get(documentId).withLock(document -> {
            Map<String, MarkSet.Placement> insertions =
                document.markPlacements(MarkTypes.SUGGEST_INSERT);
            Map<String, MarkSet.Placement> deletions =
                document.markPlacements(MarkTypes.SUGGEST_DELETE);

            List<Suggestion> result = new ArrayList<>();
            for (Row row : rows) {
                MarkSet.Placement placement =
                    (row.kind == Kind.INSERT ? insertions : deletions).get(row.id.toString());
                Range range = placement == null
                    ? null
: new Range(placement.from(), placement.to());
                result.add(new Suggestion(row.id(), documentId, row.authorId(), row.authorName(),
                    row.authorColor(), row.kind(), row.quotedText(), row.createdAt(),
                    range != null, range));
            }
            return result;
        });
    }

    private void record(UUID suggestionId, UUID documentId, String authorId, Kind kind,
                        String quoted) {
        jdbc.update("""
            INSERT INTO suggestions (id, doc_id, author_id, kind, quoted_text)
            VALUES (?, ?, ?, ?, ?)
            """, suggestionId, documentId, authorId, kind.name().toLowerCase(), quoted);
    }

    private Kind kindOf(UUID documentId, UUID suggestionId) {
        List<String> kinds = jdbc.query(
            "SELECT kind FROM suggestions WHERE id = ? AND doc_id = ? AND resolved_at IS NULL",
            (rs, rowNum) -> rs.getString("kind"), suggestionId, documentId);
        return kinds.isEmpty() ? null : Kind.valueOf(kinds.getFirst().toUpperCase());
    }

    private static Range rangeOf(RgaDocument document, String markType, String value) {
        MarkSet.Placement placement = document.markPlacements(markType).get(value);
        return placement == null ? null : new Range(placement.from(), placement.to());
    }

    private static Integer visibleIndexOf(RgaDocument document, Id id) {
        int visible = 0;
        for (Block block : document.blocks()) {
            if (block.deleted() || !block.hasContent()) {
                continue;
            }
            if (block.containsId(id)) {
                return visible + block.offsetOf(id);
            }
            visible += block.text().length();
        }
        return null;
    }

    private record Row(UUID id, String authorId, String authorName, String authorColor, Kind kind,
                       String quotedText, Instant createdAt) {
    }
}
