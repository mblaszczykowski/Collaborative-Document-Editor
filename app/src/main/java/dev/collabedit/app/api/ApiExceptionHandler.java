package dev.collabedit.app.api;

import dev.collabedit.app.auth.AuthorizationWiringException;
import dev.collabedit.app.domain.DocumentReplica;
import dev.collabedit.app.domain.DocumentService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * One place that turns an exception into a status, for every REST controller.
 *
 * <h2>Why this had to exist before the conventions could be one convention</h2>
 * There was no {@code @ControllerAdvice} anywhere, so every controller was its own set of rules and
 * they disagreed with each other in ways nobody had chosen:
 * <ul>
 * <li>"already resolved" was 409 in {@code CommentController} and 404 in
 * {@code SuggestionController} — the same situation, two answers, and the client renders them
 * differently;</li>
 * <li>{@code AccessController.removeMember} answered 204 for somebody who was not a member while
 * {@code revokeInvite}, one method away, answered 404 for an invite that did not exist;</li>
 * <li>anything a service threw arrived at the browser as a raw 500 with Spring's default body —
 * including {@code CommentService}'s {@code IllegalStateException}, which describes an
 * idempotency conflict rather than a server fault.</li>
 * </ul>
 *
 * <h2>The conventions this file and its callers now share</h2>
 * <ol>
 * <li><b>404 means "not here, as far as you are concerned".</b> That covers both "no such thing"
 * and "you may not see it", and keeping them indistinguishable is deliberate —
 * {@code DocumentAccessGuard} answers 404 rather than 403 precisely so that a caller cannot
 * learn a document exists by being refused. That discipline is correct and this file must not
 * weaken it.</li>
 * <li><b>409 means "it exists, and it is not in a state where this makes sense".</b> A thread
 * already resolved, a suggestion already decided, an owner removing their own access.</li>
 * <li><b>400 means the request was malformed.</b> A bound exceeded, a field missing, an
 * unparseable role. Never used for "the thing you named is gone" — that is 404 or 409, and
 * telling the two apart is what a client needs in order to say something true to the
 * user.</li>
 * <li><b>Every endpoint returns {@code ResponseEntity}</b>, so its status is visible at the
 * return rather than implied by a type.</li>
 * </ol>
 *
 * <h2>What is deliberately NOT mapped</h2>
 * A bare {@code RuntimeException}. A catch-all here would convert every unforeseen fault into a
 * tidy 500 with a friendly body and no stack trace — which is exactly how a bug becomes invisible.
 * Spring's default handling already answers 500 and logs; what this class adds is meaning for the
 * exceptions this project defines, not a blanket over the ones it does not.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * The body of a refusal.
     *
     * <p>Deliberately just a message. It carries no field names, no document identifiers and no
     * internal type names: several of these statuses are reached on paths where the whole point is
     * that the caller learns nothing about what exists.
     */
    public record ApiError(String message) {
    }

    /**
     * A request body that violated a {@code @Size} bound, or a missing required field.
     *
     * <p>This is what makes the bounds in item-by-item {@code @Size} annotations produce a clean
     * 400 rather than something the client has to guess at. Without validation those bodies reached
     * the CRDT layer and became a single operation of arbitrary size — an operation no frame can
     * carry and nothing can split, because its identity is what the merge algorithm is built on.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> onInvalidBody(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(error -> error.getField() + " " + error.getDefaultMessage())
            .orElse("the request body is not valid");
        return ResponseEntity.badRequest().body(new ApiError(detail));
    }

    /**
     * The same, for constraints declared on method parameters rather than on a body.
     *
     * <p>It used to ignore its argument and answer a fixed "a request parameter is not valid",
     * which threw away the one thing {@link #onInvalidBody} is careful to keep: <b>which</b>
     * parameter. The two handlers answer the same question about the same kind of mistake, so
     * answering it differently was a difference nobody had chosen — and {@code RequestBoundsIT}
     * asserts on the name for the body half, i.e. the project had already decided the name matters.
     *
     * <p>One honest imprecision: this reports the <b>Java</b> parameter's name, which is not always
     * the name the caller sent. {@code GET /api/documents?q=…} binds to a parameter called
     * {@code query}, so the message says "query", and the caller has to make the connection. Reading
     * the {@code @RequestParam} annotation to recover "q" is possible and is not done, because the
     * two names differ in exactly one place in this codebase and a mapping layer for that would be
     * more machinery than the confusion it removes. Named here so the next person meets a decision
     * rather than a bug.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> onInvalidParameter(HandlerMethodValidationException e) {
        String detail = e.getParameterValidationResults().stream()
            .flatMap(result -> result.getResolvableErrors().stream()
                .map(error -> result.getMethodParameter().getParameterName()
                    + " " + error.getDefaultMessage()))
            .findFirst()
            .orElse("a request parameter is not valid");
        return ResponseEntity.badRequest().body(new ApiError(detail));
    }

    /**
     * An argument the caller supplied that the domain refuses — an unknown visibility, say.
     *
     * <p>400 rather than 500: the request named something the API does not accept, which is the
     * caller's mistake and is describable to them.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> onIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new ApiError(e.getMessage()));
    }

    /**
     * A fault in our own wiring — 500, and nothing about it in the body.
     *
     * <p>This case used to arrive as an {@code IllegalStateException} and leave as the 409 below,
     * which was wrong in all three of its parts: the status blamed the caller for a server fault,
     * the response carried our internal diagnostic text, and nothing landed in 5xx monitoring —
     * where an endpoint that forgot its access annotation is exactly the thing somebody needs to
     * see. {@code DocumentAccessGuard} answers the same class of fault with the same status, so the
     * two halves of "we forgot to authorize something" now read alike.
     *
     * <p>ERROR rather than WARN, for the same reason: this is never the caller's doing.
     */
    @ExceptionHandler(AuthorizationWiringException.class)
    public ResponseEntity<ApiError> onWiringFault(AuthorizationWiringException e,
                                                  HttpServletRequest request) {
        log.error("Authorization wiring fault handling {} {}", request.getMethod(),
            request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ApiError("The server could not process the request."));
    }

    /**
     * A state conflict — two callers doing the same thing at once, most often.
     *
     * <p>{@code CommentService} throws this when an idempotent insert conflicts on
     * {@code client_id} and the winning row then cannot be read back, which is a genuine race
     * between two of the caller's own retries rather than a fault in the server. 409 tells the
     * client to re-read and decide; 500 told it the server was broken.
     *
     * <p>Logged at WARN with the exception, because it is also the shape a real bug would take, and
     * a 409 that turns out to be ours should be findable afterwards.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> onIllegalState(IllegalStateException e,
                                                   HttpServletRequest request) {
        log.warn("Conflict handling {} {}: {}", request.getMethod(), request.getRequestURI(),
            e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(e.getMessage()));
    }

    /**
     * An operation that can no longer be merged because GC removed its anchor.
     *
     * <p>409, for the same reason as above: the document exists and the caller may act on it, but
     * what they are describing no longer has a place to attach to. The WebSocket path answers the
     * same situation with {@code RESYNC_REQUIRED}; this is its REST equivalent, reachable through
     * the endpoints that generate operations server-side.
     */
    @ExceptionHandler(DocumentService.ResyncRequiredException.class)
    public ResponseEntity<ApiError> onResyncRequired(DocumentService.ResyncRequiredException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(e.getMessage()));
    }

    /**
     * The replica was evicted mid-operation and the single retry did not settle it.
     *
     * <p>503 with the honest meaning: nothing is wrong with the request, and repeating it will
     * almost certainly work. {@code DocumentService} retries once already — reaching here means the
     * memory limit is far below the working set, which is a capacity problem rather than a caller's
     * problem, and 5xx is where capacity problems belong.
     */
    @ExceptionHandler(DocumentReplica.StaleReplicaException.class)
    public ResponseEntity<ApiError> onStaleReplica(DocumentReplica.StaleReplicaException e) {
        log.warn("A document replica was evicted twice during one request: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(new ApiError("the document is being reloaded — retry"));
    }
}
