package dev.collabedit.app.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that an endpoint needs a logged-in caller but has no document to authorize against
 * (CP16).
 *
 * <h2>Why this is a third category and not a variant of the other two</h2>
 * Writing CP16 turned up two endpoints that fit neither {@link RequiresDocumentAccess} (there
 * is no document yet — they create one) nor {@link PublicEndpoint} (they must not run for an
 * anonymous caller). Both had the identical shape:
 *
 * <pre>{@code
 * String caller = callerId(request);
 * if (caller != null) {          // <-- and if it is null?
 *     permissions.setOwner(id, caller);
 * }
 * }</pre>
 *
 * <p>The answer to "and if it is null" was: the document is created anyway, with no owner, and
 * {@code visibility} falls back to the schema default of {@code 'link'} — which
 * {@link PermissionService} rule 4 turns into {@code EDITOR} for every logged-in user.
 *
 * <p>For {@code POST /api/documents/recovered} that is the exact inverse of what commit
 * {@code b5c6ba1} intended, and it is not a corner case: that endpoint exists <b>only</b> for
 * clients that were offline longer than the GC window (7 days), while a token lives 12 hours.
 * The population the endpoint was written for is the population whose token has certainly
 * expired, so the unauthenticated path is the <i>typical</i> path, not the edge.
 *
 * <p>Collapsing this into "public" would have kept both holes open while looking deliberate.
 * The third name exists so that "needs a user" and "needs nobody" cannot be confused.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresAuthentication {

    /** Why no document check applies here. Not optional, on purpose. */
    String value();
}
