package dev.collabedit.app.ws;

import dev.collabedit.app.auth.PermissionService;
import dev.collabedit.app.observability.DocumentMetrics;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Stops a document's traffic reaching a session whose access has been revoked (CP12, completed).
 *
 * <h2>The half of revocation that did not exist</h2>
 * {@link DocumentAccessInterceptor} is registered on the INBOUND channel only, so it decides
 * whether a frame may ENTER — a subscription request, an operation, a presence update. Nothing
 * looked at what was going out, and nothing tore an established subscription down. So after
 * {@code DELETE /api/documents/{id}/access/members/{user}} the revoked user's writes were refused,
 * their next REST request answered 404 — and <b>every broadcast kept arriving in their browser,
 * indefinitely</b>: the text other people were typing, character by character, plus the presence of
 * everyone in the room.
 *
 * <p>That was not a known simplification. {@code README.md} states that revocation takes effect
 * within the permission cache's five seconds, and {@code PLAN-v2} specified this exact interceptor
 * and named the test for it — {@code revokedAccessTerminatesLiveSubscription}. Only the inbound
 * half shipped, and the outbound half's absence is invisible from the server: the refusals in the
 * log look identical either way, because it is the WRITES that are being refused.
 *
 * <p>{@code PermissionService} caches for five seconds, so the guarantee this restores is the one
 * that was already documented: a revocation takes effect within that window, on the READ path as
 * well as the write path.
 *
 * <h2>Why a filter rather than closing the subscription</h2>
 * Un-subscribing a session from the broker would need a synthetic {@code UNSUBSCRIBE} whose
 * subscription id we do not hold, and it would have to be re-issued for every future subscription
 * of a user who might be re-granted access a moment later. Dropping the message asks the question
 * at the moment it matters and needs no state of its own. The cost is one map lookup per outbound
 * frame per subscriber, on a cache that a permission check on every inbound frame already keeps
 * warm.
 *
 * <h2>Fail closed on an ANSWER, fail open on the absence of one</h2>
 * A frame whose recipient cannot be identified is DROPPED. "We could not tell who this is, so send
 * it" is the shape of decision that produced the hole above, and an unidentifiable session is one
 * we have no safe interpretation of.
 *
 * <p>A permission lookup that THROWS is treated differently, and the distinction is deliberate: an
 * empty {@code Optional} is an answer (this user has no access), while an exception is the absence
 * of one (the database did not respond). Dropping every frame on the second would turn a Postgres
 * hiccup into a document-wide broadcast outage — the exact coupling the cluster design goes to lengths to
 * avoid everywhere else in this system — and it would do it on the path that has the weakest
 * recovery.
 *
 * <h2>Why a dropped broadcast is worse here than elsewhere, and what that implies</h2>
 * The client's only gap detector fires when it is ACKNOWLEDGED an operation it does not hold
 * ({@code connection.ts}). Acknowledgements are emitted by the instance that WROTE the operation —
 * so for an operation that arrived from the cluster, the clients of the receiving instance get the
 * broadcast and no acknowledgement at all. A broadcast dropped here therefore has <b>no detector
 * behind it</b>: the client does not know it missed anything, and stays diverged until something
 * else makes it resynchronise.
 *
 * <p>That is the reason for the asymmetry above, and the reason both drop paths are COUNTED
 * ({@code ws.frames.dropped.total}) rather than only logged. A refusal that is correct should still
 * be visible as a number; one that is a symptom must not be invisible.
 */
@Component
public class RevokedAccessFilter implements ChannelInterceptor {

    /** The destination whose entire purpose is to tell a client its access changed. */
    private static final String ACCESS_SUFFIX = "/access";


    private static final Logger log = LoggerFactory.getLogger(RevokedAccessFilter.class);

    /**
     * The reasons a frame is dropped — a CLOSED set, because it becomes a metric tag.
     *
     * <p>Same argument as {@code DocumentAccessInterceptor.Refusal}: an open set of reasons taken
     * from the frame would let a client mint metric series at will.
     */
    static final String REVOKED = "revoked";
    static final String UNKNOWN_SESSION = "unknown_session";

    private final PermissionService permissions;
    private final SessionLifecycle sessions;
    private final DocumentMetrics metrics;

    public RevokedAccessFilter(PermissionService permissions, SessionLifecycle sessions,
                               DocumentMetrics metrics) {
        this.permissions = permissions;
        this.sessions = sessions;
        this.metrics = metrics;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        SimpMessageHeaderAccessor accessor =
            MessageHeaderAccessor.getAccessor(message, SimpMessageHeaderAccessor.class);
        if (accessor == null || accessor.getMessageType() != SimpMessageType.MESSAGE) {
            // CONNECTED frames, receipts and heartbeats carry no document and no permission
            // question. Only payload deliveries are filtered.
            return message;
        }

        UUID documentId = DocumentAccessInterceptor.documentIdOf(accessor.getDestination());
        if (documentId == null) {
            // The three private user queues (/user/queue/session, /sync, /errors) and anything else
            // without a document in its destination. A sync response is already the answer to an
            // inbound frame the interceptor authorized, so re-deciding it here would be asking the
            // same question twice and answering it from a different instant.
            return message;
        }

        if (accessor.getDestination().endsWith(ACCESS_SUFFIX)) {
            // THE ONE DESTINATION A REVOKED USER MUST STILL RECEIVE, and the filter dropped it.
            //
            // `AccessChanged` is how a client learns to re-ask what its role is now, and it is the
            // only thing that makes `useCollaborativeDocument` show "you no longer have access".
            // Filtering it meant the revoked tab simply went quiet: still showing the document,
            // still letting the user type into a replica whose operations the inbound interceptor
            // was refusing. The one frame whose whole purpose is to tell somebody they have been
            // revoked was the one frame the revocation stopped.
            //
            // Safe to deliver by construction rather than by exception: the record is EMPTY. CP21
            // removed its user id and role fields precisely so this broadcast cannot say anything
            // `GET /access` would refuse — see Messages.AccessChanged. What it discloses to a
            // revoked user is that something about access changed, which they are about to find
            // out by being refused anyway.
            return message;
        }

        String sessionId = accessor.getSessionId();
        String userId = sessions.userOf(sessionId);
        if (userId == null) {
            // Either the session ended between the broker resolving its subscribers and this frame
            // reaching the channel — in which case dropping it costs nothing, there is nobody
            // there — or a frame arrived for a session this instance never saw authenticate, which
            // is a state we have no safe interpretation of.
            metrics.recordOutboundDrop(UNKNOWN_SESSION);
            log.debug("Dropping a frame for document {} to session {}: no identity for that "
                + "session", documentId, sessionId);
            return null;
        }

        boolean stillPermitted;
        try {
            stillPermitted = permissions.roleOf(userId, documentId).isPresent();
        } catch (RuntimeException e) {
            // DELIVERED, not dropped, and this is the one place in this class that fails open.
            //
            // roleOf answers from a five-second cache and falls through to a database query on a
            // miss, so this catch is the connection pool timing out or Postgres being unreachable.
            // That is not an answer about this user; it is the absence of an answer, and the two
            // deserve opposite treatment. Dropping here would mean a database blip stops the
            // broadcast to EVERY client of EVERY document for as long as it lasts — and, because a
            // cluster-borne operation carries no acknowledgement, the clients would not detect the
            // loss and would stay diverged after the blip ended.
            //
            // What it costs when this catch is wrong: a revoked user keeps receiving for as long
            // as the database cannot answer. Bounded by an outage during which nothing can be
            // written anyway, and loud in the log rather than silent.
            log.warn("Could not check access to document {} for user {} while delivering a frame; "
                + "delivering it rather than stopping the document's broadcasts", documentId,
                userId, e);
            return message;
        }

        if (!stillPermitted) {
            metrics.recordOutboundDrop(REVOKED);
            log.info("Dropping a broadcast of document {} to user {}: their access has been "
                + "revoked", documentId, userId);
            return null;
        }
        return message;
    }
}
