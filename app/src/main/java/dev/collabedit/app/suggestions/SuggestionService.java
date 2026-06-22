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
 * sequence does not change by a single field — which is the claim the mark layer made and this is the
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
 * The client names positions by <b>character identifier</b>, the way cursors and marks
 * do. An offset sent to the server is stale the moment somebody else types before it,
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

        // The row FIRST, before a single operation touches the replica. See record() for why
        // this order is not a matter of taste.
        record(suggestionId, documentId, authorId, Kind.INSERT, text);

        // Generated AND enqueued under ONE lock acquisition. Both used to be separate steps —
        // withLock here, then publish asking the registry for the replica again — with an
        // eviction window between them in which the operations were enqueued on a replica that
        // never saw them. See DocumentService.generateAndSubmit.
        List<Op> generated = documents.generateAndSubmit(documentId, document -> {
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
        }, null);

        if (generated == null) {
            // The anchor is gone. The row stays, listed as unanchored — a proposal nobody can
            // apply, which is exactly what happened, and is visible rather than silent.
            return Optional.empty();
        }
        log.info("Suggestion {} (insert) proposed in document {} by {}", suggestionId, documentId,
            authorId);
        return Optional.of(suggestionId);
    }

    /** Proposes deleting the range from {@code fromId} to {@code toId}, both inclusive. */
    public Optional<UUID> proposeDeletion(UUID documentId, String authorId, Id fromId, Id toId) {
        UUID suggestionId = UUID.randomUUID();

        // The quoted text has to be read before the row can be written, and the row has to be
        // written before the mark is applied — so this path reads first, WITHOUT changing
        // anything, and does the work in a second pass.
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
            // Somebody deleted the range between the two passes. The row stays as an unanchored
            // proposal rather than becoming a mark over text that is not there.
            return Optional.empty();
        }
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
     * <h2>Why this is idempotent: the ROW is the arbiter</h2>
     * Two people may press the button at the same instant, from two instances. The row is settled
     * first, and {@code resolved_at IS NULL} is part of the update, so exactly one caller sees
     * {@code updated == 1} — and only that one touches the document.
     *
     * <p>This paragraph used to describe the previous mechanism, which the ordering below
     * deliberately replaced: "the second call finds no mark, because the first one removed it".
     * That relied on the loser reaching the replica after the winner had already changed it, which
     * is a race narrowed rather than closed. The second caller never reaches the mark check now.
     *
     * <p>The same mechanism covers the case where the anchored text was deleted by somebody
     * editing normally: there is nothing to accept or reject, so the suggestion is resolved with
     * no operations. It stops being open, which is what the reader needs, and nothing pretends
     * the proposal was applied.
     *
     * @return which of the three things happened; see {@link Outcome}
     */
    public Outcome resolve(UUID documentId, UUID suggestionId, String userId, boolean accept) {
        Kind kind = kindOf(documentId, suggestionId);
        if (kind == null) {
            // Not there, or already decided — and the caller needs to know WHICH, because it tells
            // the reader two different things about why their screen is out of date. The extra
            // query costs nothing: it runs only on the path that is about to answer an error.
            return existsAtAll(documentId, suggestionId) ? Outcome.ALREADY_RESOLVED : Outcome.UNKNOWN;
        }

        String markType = kind == Kind.INSERT
            ? MarkTypes.SUGGEST_INSERT
            : MarkTypes.SUGGEST_DELETE;
        // Accepting an insertion and rejecting a deletion both mean "the text stays, the mark
        // goes". Rejecting an insertion and accepting a deletion both mean "the text goes".
        boolean textStays = accept == (kind == Kind.INSERT);

        // The row is settled FIRST, and that makes it the arbiter of the race rather than the
        // replica lock: exactly one caller sees `updated == 1`, and only that one touches the
        // document. The previous order — apply, then record — relied on the loser finding the
        // mark already gone, which was true and left a worse failure behind it: if the UPDATE
        // threw, the operations were already in the in-memory replica and had been neither
        // written nor broadcast, so the server's document silently disagreed with its own log.
        int updated = jdbc.update("""
            UPDATE suggestions
               SET resolved_at = now(), resolved_by = ?, resolution = ?
             WHERE id = ? AND doc_id = ? AND resolved_at IS NULL
            """, userId, accept ? "accepted" : "rejected", suggestionId, documentId);
        if (updated == 0) {
            // Somebody else's UPDATE won the race and settled the row first.
            return Outcome.ALREADY_RESOLVED;
        }

        // Generated and enqueued together — see DocumentService.generateAndSubmit for the eviction
        // window that splitting them leaves open.
        documents.generateAndSubmit(documentId, document -> {
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
        }, null);

        log.info("Suggestion {} {} by {}", suggestionId, accept ? "accepted" : "rejected", userId);
        return Outcome.RESOLVED;
    }

    /**
     * What resolving did.
     *
     * <p>Three values rather than a boolean, because {@code false} meant two situations that a
     * client has to describe differently: a proposal that is not there at all (a stale link, or the
     * wrong document) and one that somebody else has already accepted or rejected (a stale list, on
     * a document being worked on right now). The controller maps them to 404 and 409.
     */
    public enum Outcome {
        RESOLVED, ALREADY_RESOLVED, UNKNOWN
    }

    /** Whether the suggestion exists at all, resolved or not — only asked on the failure path. */
    private boolean existsAtAll(UUID documentId, UUID suggestionId) {
        return !jdbc.queryForList("SELECT 1 FROM suggestions WHERE id = ? AND doc_id = ?",
            Integer.class, suggestionId, documentId).isEmpty();
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
            // Both sweeps taken ONCE for the whole list, not once per row. Each one resolves every
            // mark anchor in the document, and this method is what the suggestions panel polls —
            // so with twenty open proposals on a heavily formatted document it was twenty full
            // sweeps, all of them holding the same lock every keystroke also needs.
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

    // ------------------------------------------------------------------

    /**
     * Writes the suggestion row.
     *
     * <h2>Why every path here writes the row BEFORE touching the replica</h2>
     * Operations applied to the in-memory replica are only durable once {@code publish} hands
     * them to {@code DocumentService}, which writes and broadcasts them. Anything that throws
     * between the two leaves the server's document holding text nobody else has and nothing can
     * reproduce — the state diverges from its own log, and the divergence surfaces at the next
     * restart, as text disappearing.
     *
     * <p>With the row first, the same failure produces a suggestion listed as unanchored: a
     * proposal that cannot be applied, which is visible, resolvable, and true.
     *
     * <p>The rule is the same one the operation-log decision applies to the acknowledgement: when two orderings
     * both have a failure window, take the one whose failure is the smaller lie.
     */
    private void record(UUID suggestionId, UUID documentId, String authorId, Kind kind,
                        String quoted) {
        jdbc.update("""
            INSERT INTO suggestions (id, doc_id, author_id, kind, quoted_text)
            VALUES (?, ?, ?, ?, ?)
            """, suggestionId, documentId, authorId, kind.name().toLowerCase(), quoted);
    }

    // publish used to sit here, looping over already-generated operations and handing each to
    // documents.submitAlreadyMerged — which asks the registry for the replica AGAIN, after the
    // generating lock has been released. Every caller now generates and enqueues in one step
    // instead; see DocumentService.generateAndSubmit for what could happen in between.

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
     * shifts it, and that is precisely what anchors are for.
     *
     * <h2>Why not {@code formatting}</h2>
     * This used to scan the resolved spans for ones carrying this suggestion's identifier, on the
     * stated assumption that "spans carrying the same mark type for the same suggestion are
     * contiguous by construction". The assumption is false as soon as a second proposal of the same
     * kind overlaps the first: the render resolves ONE value per type per segment, so the older
     * proposal was left holding whichever sub-range it happened to win.
     *
     * <p>Both directions of that were wrong and silent. Accepting a suggested deletion of
     * {@code "The quick"} that another proposal overlapped deleted only {@code "The "} and answered
     * 204; rejecting a suggested insertion nested inside another one deleted the other one's
     * characters. {@code markPlacements} asks the mark layer for this operation's own range and is
     * blind to who wins the render.
     */
    private static Range rangeOf(RgaDocument document, String markType, String value) {
        MarkSet.Placement placement = document.markPlacements(markType).get(value);
        return placement == null ? null : new Range(placement.from(), placement.to());
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
