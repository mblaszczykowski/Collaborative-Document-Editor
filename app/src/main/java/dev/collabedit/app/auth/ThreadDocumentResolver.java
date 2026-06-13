package dev.collabedit.app.auth;

import java.util.UUID;

/**
 * Finds the document a comment thread belongs to.
 *
 * <p>Exists so that {@link DocumentAccessGuard} can authorize {@code /api/comments/{threadId}/…}
 * without the {@code auth} package depending on the {@code comments} package. The guard needs
 * one question answered, so it asks for exactly that question rather than for the service that
 * happens to know the answer.
 *
 * @return the owning document, or {@code null} when the thread does not exist
 */
@FunctionalInterface
public interface ThreadDocumentResolver {
    UUID documentOf(UUID threadId);
}
