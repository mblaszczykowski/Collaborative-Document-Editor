package dev.collabedit.app.auth;

/**
 * An authenticated endpoint was reached without a principal — <b>a fault in our wiring, not in the
 * caller's request</b>.
 *
 * <h2>Why this type exists rather than a bare IllegalStateException</h2>
 * {@link TokenAuthFilter#requiredCallerId} threw one, and its javadoc justified throwing by saying
 * the fault "is answered with a 500, exactly as {@code DocumentAccessGuard} answers it". It was
 * not. {@code ApiExceptionHandler} maps <b>every</b> {@code IllegalStateException} to <b>409</b>
 * with {@code e.getMessage()} in the body — a mapping that is right for what it was written for (a
 * genuine race between a caller's own retries) and wrong for this.
 *
 * <p>Three things followed from sharing the type. The status told the caller they had made a
 * mistake when the server had. The fault did not appear in 5xx monitoring, which is the one place
 * somebody would notice an endpoint that forgot its annotation. And the body carried our internal
 * wording — "the access guard did not run for it" — out to a client, in an application whose
 * {@code ApiError} javadoc promises it "carries no field names, no document identifiers and no
 * internal type names".
 *
 * <p>Reachability is low by construction: every call site sits behind an annotation the guard
 * enforces, so this fires only when the guard is mis-wired. That bounds how often it matters, not
 * whether it is right — and a defensive throw whose whole purpose is to be loud was being reported
 * as somebody else's problem.
 */
public class AuthorizationWiringException extends RuntimeException {

    public AuthorizationWiringException(String message) {
        super(message);
    }
}
