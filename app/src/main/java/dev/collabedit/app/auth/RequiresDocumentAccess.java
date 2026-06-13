package dev.collabedit.app.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that an endpoint touches one document and what it needs to be allowed to do (CP16).
 *
 * <p>Enforced at runtime by {@link DocumentAccessGuard} and at build time by
 * {@code EndpointAuthorizationArchitectureTest}. Neither alone is enough: the guard cannot
 * see an endpoint nobody wrote yet, and the architecture test cannot stop a running server.
 *
 * <h2>Why an annotation and not just a path rule</h2>
 * A path rule ({@code /api/documents/{id}/**}) would have covered every hole this project has
 * actually had — the IDOR from CP11 and the history controller from CP14 both sat under that
 * prefix. It would <b>not</b> cover {@code /api/comments/{threadId}/…}, which reaches the same
 * document through a different noun, and it cannot express that restore needs write access
 * while preview needs read.
 *
 * <p>So the declaration lives at the point of use, where the person adding an endpoint is
 * already looking, and the guard refuses to serve anything that failed to declare. The cost is
 * one line per endpoint; the benefit is that <b>forgetting is not a silent state</b>.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresDocumentAccess {

    /** What the endpoint needs to be permitted to do. */
    Capability value();

    /** Name of the path variable carrying the identifier. */
    String param() default "id";

    /** Which identifier {@link #param()} holds. */
    Source from() default Source.DOCUMENT;

    /**
     * Whether a soft-deleted document still counts.
     *
     * <p>Default {@code false}, because for opening and editing a document in the trash must
     * behave as if it did not exist. Trash listing and restore are the deliberate exceptions —
     * they operate on deleted documents by definition, and must not skip the permission check
     * while doing so, or the trash becomes a list of other people's titles.
     */
    boolean includeDeleted() default false;

    enum Source {
        /** The path variable is the document id. */
        DOCUMENT,
        /**
         * The path variable is a comment thread id; the document is looked up from it.
         *
         * <p>This is the case a pure path rule cannot express, and therefore the reason the
         * declaration is an annotation rather than a line of configuration.
         */
        COMMENT_THREAD
    }
}
