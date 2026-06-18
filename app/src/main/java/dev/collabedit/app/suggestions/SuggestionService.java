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
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Suggestion mode — proposing a change without the permission to make one (CP23).
 *
 * <h2>What this is, in one sentence</h2>
 * A suggested insertion is <b>real text in the real character sequence</b> wearing a mark that
 * says it is a proposal; a suggested deletion is a mark over text that is still there. Accepting
 * an insertion removes the mark, rejecting it deletes the characters, and the two are exactly
 * reversed for a deletion.
 *
 * <h2>Why the mark layer again</h2>
 * This is the third feature built out of the layer CP13 introduced for formatting, after comments
 * (CP14), and the first one nobody had in mind when that layer was designed. The character
 * sequence does not change by a single field — which is the claim ADR-0010 made and this is the
 * strongest evidence for it so far, because a feature the design never anticipated fitted anyway.
 *
 * <h2>Why the server generates the operations</h2>
 * A proposed insertion needs an {@code Insert}, and a {@code commenter} may not send one — that
 * is what the role means. So the proposal arrives over REST and the operations are generated
 * <b>here</b>, on the server's replica, exactly as {@code HistoryController} does for a restore.
 *
 * <p>The alternative was to let a commenter send inserts and have the STOMP interceptor verify
 * that each one is covered by a suggestion mark. That is authorization reading an operation's
 * payload in order to decide whether the operation is allowed — a pattern the README already
 * lists as a debt in the one place it exists. Two instances of a debt is a design.
 *
 * <h2>Anchors, not offsets — for the third time in this project</h2>
 * The client names positions by <b>character identifier</b>, the way cursors (ADR-0006) and marks
 * (ADR-0010) do. An offset sent to the server is stale the moment somebody else types before it,
 * and the failure it produces is a proposal attached to the wrong words — silently, and in
 * somebody else's name.
 */
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

    /**
     * A suggestion as a reader of the list sees it.
     *
     * @param anchored whether the proposal still has a place in the document. Computed from the
     *                 CRDT layer rather than stored, exactly like a comment thread's anchoring:
     *                 storing it would mean a second copy of a fact the document already holds
     * @param range    where it currently sits, or {@code null} when it no longer does
     */
    public record Suggestion(UUID id, UUID documentId, String authorId, String authorName,
                             String authorColor, Kind kind, String quotedText, Instant createdAt,
                             boolean anchored, Range range) {
    }

    public record Range(int from, int to) {
    }

    // ------------------------------------------------------------------
    // Proposing
    // ------------------------------------------------------------------

    /**
     * Proposes inserting {@code text} after the character {@code afterId} ({@code null} = at the
     * start of the document).
     *
     * @return the new suggestion, or empty when the anchor no longer exists — somebody deleted
     *         the place this was going to be attached to while it was being written
     */
    public Optional<UUID> proposeInsertion(UUID documentId, String authorId, Id afterId,
                                           String text) {
        if (text == null || text.isEmpty()) {
            return Optional.empty();
        }
        UUID suggestionId = UUID.randomUUID();

        List<Op> generated = registry.get(documentId).withLock(document -> {
            Integer anchor = afterId == null ? -1 : visibleIndexOf(document, afterId);
            if (anchor == null) {
                return null;
            }
            int position = anchor + 1;
            List<Op> ops = new ArrayList<>();
            ops.add(document.insert(position, text));
            // The mark covers exactly what was inserted. It is added after the insertion rather
            // than around it, because the range is expressed in visible positions and those only
            // exist once the characters do.
            ops.add(document.addMark(position, position + text.length(),
                MarkTypes.SUGGEST_INSERT, suggestionId.toString()));
            return ops;
        });

        if (generated == null) {
            return Optional.empty();
        }
        record(suggestionId, documentId, authorId, Kind.INSERT, text);
        publish(documentId, generated);
        log.info("Suggestion {} (insert) proposed in document {} by {}", suggestionId, documentId,
            authorId);
        return Optional.of(suggestionId);
    }

    /** Proposes deleting the range from {@code fromId} to {@code toId}, both inclusive. */
    public Optional<UUID> proposeDeletion(UUID documentId, String authorId, Id fromId, Id toId) {
        UUID suggestionId = UUID.randomUUID();

        record Proposal(List<Op> ops, String quoted) {
        }

        Proposal proposal = registry.get(documentId).withLock(document -> {
            Integer from = visibleIndexOf(document, fromId);
            Integer to = visibleIndexOf(document, toId);
            if (from == null || to == null || to < from) {
                return null;
            }
            String quoted = document.text().substring(from, to + 1);
            return new Proposal(List.of(document.addMark(from, to + 1,
                MarkTypes.SUGGEST_DELETE, suggestionId.toString())), quoted);
        });

        if (proposal == null) {
            return Optional.empty();
        }
        record(suggestionId, documentId, authorId, Kind.DELETE, proposal.quoted());
        publish(documentId, proposal.ops());
        log.info("Suggestion {} (delete) proposed in document {} by {}", suggestionId, documentId,
            authorId);
        return Optional.of(suggestionId);
    }

    // ------------------------------------------------------------------
    // Resolving
    // ------------------------------------------------------------------

    /**
     * Accepts or rejects a suggestion.
     *
     * <h2>Why this is idempotent by construction</h2>
     * Two people may press the button at the same instant, from two instances. The second call
     * finds no mark — the first one removed it — and generates nothing, which is the correct
     * outcome rather than a race that has to be prevented. The row records the first resolution;
     * a second one does not overwrite it, because {@code resolved_at IS NULL} is part of the
     * update.
     *
     * <p>The same mechanism covers the case where the anchored text was deleted by somebody
     * editing normally: there is nothing to accept or reject, so the suggestion is resolved with
     * no operations. It stops being open, which is what the reader needs, and nothing pretends
     * the proposal was applied.
     *
     * @return {@code false} when the suggestion is unknown or already resolved
     */
    public boolean resolve(UUID documentId, UUID suggestionId, String userId, boolean accept) {
        Kind kind = kindOf(documentId, suggestionId);
        if (kind == null) {
            return false;
        }

        String markType = kind == Kind.INSERT
            ? MarkTypes.SUGGEST_INSERT
            : MarkTypes.SUGGEST_DELETE;
        // Accepting an insertion and rejecting a deletion both mean "the text stays, the mark
        // goes". Rejecting an insertion and accepting a deletion both mean "the text goes".
        boolean textStays = accept == (kind == Kind.INSERT);

        List<Op> generated = registry.get(documentId).withLock(document -> {
            Range range = rangeOf(document, markType, suggestionId.toString());
            if (range == null) {
                return List.<Op>of();
            }
            List<Op> ops = new ArrayList<>();
            if (textStays) {
                ops.add(document.removeMark(range.from(), range.to(), markType));
            } else {
                // The mark is left where it is: it now covers deleted characters, so it resolves
                // to no span and shows nowhere. Removing it first would be a second operation
                // doing nothing visible, and its anchors are tombstones either way.
                ops.add(document.delete(range.from(), range.to() - range.from()));
            }
            return ops;
        });

        int updated = jdbc.update("""
            UPDATE suggestions
               SET resolved_at = now(), resolved_by = ?, resolution = ?
             WHERE id = ? AND doc_id = ? AND resolved_at IS NULL
            """, userId, accept ? "accepted" : "rejected", suggestionId, documentId);
        if (updated == 0) {
            return false;
        }

        publish(documentId, generated);
        log.info("Suggestion {} {} by {}", suggestionId, accept ? "accepted" : "rejected", userId);
        return true;
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /** Open suggestions for a document, with where each one currently sits. */
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
            List<Suggestion> result = new ArrayList<>();
            for (Row row : rows) {
                String markType = row.kind() == Kind.INSERT
                    ? MarkTypes.SUGGEST_INSERT
                    : MarkTypes.SUGGEST_DELETE;
                Range range = rangeOf(document, markType, row.id().toString());
                result.add(new Suggestion(row.id(), documentId, row.authorId(), row.authorName(),
                    row.authorColor(), row.kind(), row.quotedText(), row.createdAt(),
                    range != null, range));
            }
            return result;
        });
    }

    // ------------------------------------------------------------------

    private void record(UUID suggestionId, UUID documentId, String authorId, Kind kind,
                        String quoted) {
        jdbc.update("""
            INSERT INTO suggestions (id, doc_id, author_id, kind, quoted_text)
            VALUES (?, ?, ?, ?, ?)
            """, suggestionId, documentId, authorId, kind.name().toLowerCase(), quoted);
    }

    private void publish(UUID documentId, List<Op> generated) {
        for (Op op : generated) {
            // Already merged on the server replica, where they were created — this only writes
            // and broadcasts them. The same path a restore takes, for the same reason.
            documents.submitAlreadyMerged(documentId, op, null);
        }
    }

    private Kind kindOf(UUID documentId, UUID suggestionId) {
        List<String> kinds = jdbc.query(
            "SELECT kind FROM suggestions WHERE id = ? AND doc_id = ? AND resolved_at IS NULL",
            (rs, rowNum) -> rs.getString("kind"), suggestionId, documentId);
        return kinds.isEmpty() ? null : Kind.valueOf(kinds.getFirst().toUpperCase());
    }

    /**
     * Where a suggestion's mark currently is, in visible positions.
     *
     * <p>Read from the document rather than stored, because it moves: every insertion before it
     * shifts it, and that is precisely what anchors are for. Spans carrying the same mark type
     * for the same suggestion are contiguous by construction, so the first and last of them
     * bound the range.
     */
    private static Range rangeOf(RgaDocument document, String markType, String value) {
        int from = -1;
        int to = -1;
        for (MarkSet.Span span : document.formatting()) {
            if (value.equals(span.marks().get(markType))) {
                if (from < 0) {
                    from = span.from();
                }
                to = span.to();
            }
        }
        return from < 0 ? null : new Range(from, to);
    }

    /**
     * The visible position of a character, or {@code null} when it is gone.
     *
     * <p>A linear walk, and deliberately not a new method on {@code RgaDocument}: the index there
     * answers the question that runs on every keystroke, and this one runs when somebody proposes
     * a change — a gesture measured in seconds. Adding an index for it would be paying on the hot
     * path for something used on the cold one.
     */
    private static Integer visibleIndexOf(RgaDocument document, Id id) {
        int visible = 0;
        for (Block block : document.blocks()) {
            if (block.deleted() || !block.hasContent()) {
                continue;
            }
            // containsId first: offsetOf is arithmetic on the identifier and returns a number
            // for any input, meaningful only inside the block it belongs to. Trusting it
            // unguarded would resolve a foreign identifier to a plausible-looking position.
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
