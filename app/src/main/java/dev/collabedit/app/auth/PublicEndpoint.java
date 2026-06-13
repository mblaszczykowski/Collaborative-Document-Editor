package dev.collabedit.app.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that an endpoint deliberately needs no per-document authorization (CP16).
 *
 * <p>The counterpart to {@link RequiresDocumentAccess}. Every request-mapped method must carry
 * one or the other, or {@link DocumentAccessGuard} refuses to serve it.
 *
 * <h2>Why the reason is mandatory</h2>
 * There is no default value, so the compiler will not let anyone silence the guard without
 * writing down <b>why</b>. This is the whole point of the annotation: an endpoint that skips
 * authorization should cost a sentence of justification, visible in the diff, rather than being
 * indistinguishable from an endpoint where somebody simply forgot.
 *
 * <p>The set of endpoints carrying this annotation is additionally pinned by name in
 * {@code EndpointAuthorizationArchitectureTest}, so adding one is a two-place change and shows
 * up in review as what it is.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PublicEndpoint {

    /** Why this endpoint is safe without a per-document check. Not optional, on purpose. */
    String value();
}
