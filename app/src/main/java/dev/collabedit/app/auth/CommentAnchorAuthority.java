package dev.collabedit.app.auth;

import java.util.UUID;

/**
 * Whether a caller may attach a comment anchor to a given thread.
 *
 * <p>The second question the {@code commenter} role needs answered about a frame, and the reason it
 * exists is that the first one is not enough. {@code DocumentAccessInterceptor} lets a commenter
 * send exactly one kind of operation — an {@code AddMark} of type {@code comment} — and used to
 * check only the operation type and the mark type. It never looked at the mark's <b>value</b>, which
 * is the thread identifier.
 *
 * <p>That mattered because {@code MarkSet.placementsOf} merges every placement sharing a value with
 * {@code min(from)}/{@code max(to)}. A commenter who knew another thread's identifier — and they do,
 * {@code GET /comments} returns them — could add a {@code comment} mark carrying it over the whole
 * document and stretch somebody else's highlight across all of it, or mint an anchor for a thread
 * that does not exist.
 *
 * <p>Shaped as a one-question interface for the same reason as {@link ThreadDocumentResolver}: the
 * {@code auth} and {@code ws} packages need an answer, not a dependency on {@code comments}. The
 * implementation is {@code CommentService}.
 *
 * @see ThreadDocumentResolver
 */
@FunctionalInterface
public interface CommentAnchorAuthority {

    /**
     * @param userId the caller
     * @param documentId the document the frame was authorized against
     * @param threadId the value carried by the {@code comment} mark
     * @return {@code true} when that thread exists, belongs to that document, and was opened by
     *     that caller
     */
    boolean mayAnchorThread(String userId, UUID documentId, UUID threadId);
}
