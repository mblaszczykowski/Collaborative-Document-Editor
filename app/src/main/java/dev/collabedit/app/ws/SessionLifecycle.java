package dev.collabedit.app.ws;

import dev.collabedit.app.observability.DocumentMetrics;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * What happens when a WebSocket session begins and ends — <b>in one place, in a stated order</b>.
 *
 * <h2>What this replaces</h2>
 * One event, a session ending, was handled in three unrelated files:
 * <ul>
 * <li>presence, in an anonymous {@code ChannelInterceptor} inside {@code StompConnectInterceptor}
 * — a class whose stated job is authentication;</li>
 * <li>the rate limiter's bucket maps, in {@code DocumentAccessInterceptor.preSend} — a class
 * whose stated job is authorization;</li>
 * <li>the close-reason metric, in {@code observability/SessionCloseListener}.</li>
 * </ul>
 * Each was reasonable where it stood and nothing showed the order between them, so "what is torn
 * down, and after what" was a question with no single place to look. It also meant the session
 * counters were incremented from a fourth place again — {@code PresenceService.join}, which is a
 * document join and not a session at all.
 *
 * <h2>The race this closes</h2>
 * Teardown used to hang off the inbound {@code DISCONNECT} frame, and {@code clientInboundChannel}
 * is an {@code ExecutorSubscribableChannel} with no {@code preserveReceiveOrder}. Two frames of the
 * SAME session therefore run on different threads with no ordering between them, so a queued
 * {@code /join} can be dispatched AFTER the disconnect has already been processed. What that
 * produced was concrete rather than theoretical:
 * <ul>
 * <li>{@code PresenceService.bySession} regained an entry that nothing would ever remove — the
 * disconnect that would have removed it has already been and gone — so a departed
 * participant stayed in the list for the lifetime of the process;</li>
 * <li>{@code ws.sessions.opened.total} counted that session twice;</li>
 * <li>{@code ws.sessions.active} was incremented a second time and decremented once, so the
 * gauge drifted upwards permanently.</li>
 * </ul>
 *
 * <p>The fix is to make the live-session map the arbiter, and to make "is it still live" and
 * "record the join" ONE step. {@link #joined} runs the presence bookkeeping inside
 * {@code computeIfPresent} and {@link #end} removes the key inside {@code computeIfPresent} — both
 * operations lock the same {@code ConcurrentHashMap} bin for that key, so they cannot interleave.
 * A join for a session that has ended finds nothing and does nothing.
 *
 * <p>Only the map mutations are inside the compute; the broadcasts are outside it. Holding a bin
 * lock across a message dispatch would trade one narrow race for a wide one.
 *
 * <h2>Two triggers, one teardown</h2>
 * {@link #end} is called from the inbound {@code DISCONNECT} frame (promptly, before the socket has
 * finished closing) and from {@link SessionDisconnectEvent} (reliably, including when the client
 * vanishes without a frame). Spring's own documentation says that event may be published more than
 * once per session, so being idempotent is a requirement rather than a nicety — and removing from a
 * map is exactly that: the second call finds nothing and stops.
 */
@Component
public class SessionLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SessionLifecycle.class);

    /**
     * Live sessions: STOMP session id -> the user established at {@code CONNECT}.
     *
     * <h2>Why the identity is kept here at all</h2>
     * It is the only place the OUTBOUND channel can learn who a frame is going to.
     * {@code SimpleBrokerMessageHandler} builds each subscriber's copy of a broadcast with
     * {@code setSessionId(...)} followed by {@code copyHeadersIfAbsent(originalMessage)} — so the
     * session id is the recipient's, and the session ATTRIBUTES are whatever the publisher had,
     * which for {@code convertAndSend("/topic/…")} is nothing. {@link RevokedAccessFilter} therefore
     * cannot read the recipient's identity off the message, and asks here instead.
     */
    private final Map<String, String> userBySession = new ConcurrentHashMap<>();

    /**
     * The user behind each CONNECTED replica identifier — the basis for refusing a forged one.
     *
     * <h2>Why liveness is the right grain, and the two grains that are not</h2>
     * An operation names the replica that produced it, and nothing used to check that the sender
     * was that replica. Anyone with write access could read a peer's identifier out of a presence
     * frame and mint operations under it; the peer's own operations then collide on
     * {@code (replicaId, lamport)} and are swallowed as duplicates, silently.
     *
     * <p><b>Session equality is too strict.</b> The server allocates a fresh identifier at every
     * {@code CONNECT}, and the client legitimately resends operations created under a PREVIOUS one
     * — that is the offline buffer working as designed.
     *
     * <p><b>The user is too strict as well, which is less obvious and was the first answer here.</b>
     * It assumed the user identifier persists across reconnects. It does not: {@code UserDirectory}
     * mints a fresh {@code UUID} on every login and {@code session.ts} keeps it in
     * {@code sessionStorage}, which is scoped to the TAB. So close the tab, reopen it, log in
     * again, and the operations restored from IndexedDB — the ones written offline, the whole point
     * of the buffer — arrive under a replica identifier the log records against a user id that no
     * longer exists anywhere. Verified: they were refused with {@code REPLICA_ID_CONFLICT} and
     * resent on every reconnect for ever.
     *
     * <p>So the question is not "who owned this identifier once" but "is somebody using it NOW".
     * A live session holding it is exactly the case the forgery needs and the case offline replay
     * never involves, because the session that created those operations is gone by definition.
     *
     * <p><b>The limit, stated:</b> this does not stop somebody minting operations under the
     * identifier of a peer who has disconnected. That case is indistinguishable from the offline
     * replay it must not refuse, and choosing to refuse it would break the feature to close a
     * narrower hole.
     */
    private final Map<Integer, String> userByReplica = new ConcurrentHashMap<>();

    private final RateLimiter rateLimiter;

    /**
     * Lazily, for the same reason {@code StompConnectInterceptor} takes it lazily: this component
     * is reached from the WebSocket configurer, and {@code PresenceService} needs
     * {@code SimpMessagingTemplate}, which comes out of the very infrastructure that configurer
     * builds. Spring reports the cycle as {@code BeanCurrentlyInCreationException} at startup.
     */
    private final ObjectProvider<PresenceService> presence;
    private final DocumentMetrics metrics;

    public SessionLifecycle(RateLimiter rateLimiter, ObjectProvider<PresenceService> presence,
                            DocumentMetrics metrics) {
        this.rateLimiter = rateLimiter;
        this.presence = presence;
        this.metrics = metrics;
    }

    /**
     * A session has authenticated. Called from the {@code CONNECT} handler, once.
     *
     * <p>This is where {@code ws.sessions.opened.total} is incremented, and the move is the point:
     * it used to be incremented when a session JOINED A DOCUMENT, which is a different event that
     * happens zero times for a client that connects and never opens anything, and twice under the
     * frame-ordering race above.
     */
    void begin(String sessionId, String userId) {
        if (sessionId == null) {
            // A CONNECT with no session id is not something Spring produces; if it ever did, the
            // session would be untrackable and every outbound frame to it would fail closed.
            log.warn("A CONNECT frame arrived with no session id — the session cannot be tracked");
            return;
        }
        userBySession.put(sessionId, userId);
        metrics.sessionOpened();
    }

    /** The user behind a live session, or {@code null} once it has ended. */
    public String userOf(String sessionId) {
        return sessionId == null ? null : userBySession.get(sessionId);
    }

    /**
     * The user currently connected under a replica identifier, or {@code null} if nobody is.
     *
     * <p>See {@link #userByReplica} for why this is asked of live sessions rather than of the
     * operation log.
     */
    public String userOfReplica(int replicaId) {
        return userByReplica.get(replicaId);
    }

    /** Which replica identifier a live session was given, for the teardown below. */
    private final Map<String, Integer> replicaBySession = new ConcurrentHashMap<>();

    /**
     * A session joined a document — recorded only if the session is still live.
     *
     * <p>The presence bookkeeping happens inside {@code computeIfPresent} so that it cannot
     * interleave with {@link #end}; the broadcasts happen outside it, because dispatching messages
     * while holding a map bin lock is a worse problem than the one being solved.
     */
    void joined(String sessionId, UUID documentId, int replicaId, String userId,
                String displayName, String color) {
        UUID[] leftBehind = new UUID[1];
        boolean live = userBySession.computeIfPresent(sessionId, (id, user) -> {
            leftBehind[0] = presence.getObject()
                .recordJoin(sessionId, documentId, replicaId, userId, displayName, color);
            replicaBySession.put(sessionId, replicaId);
            userByReplica.put(replicaId, userId);
            return user;
        }) != null;

        if (!live) {
            // Not an error and not silent. It means the session's teardown won the race with its
            // own join frame, which is exactly what this class exists to make survivable — but a
            // rate above zero here says the inbound channel is reordering frames often, and that is
            // worth being able to see.
            log.debug("Ignoring a join for session {}, which has already ended", sessionId);
            return;
        }
        if (leftBehind[0] != null) {
            presence.getObject().announce(leftBehind[0]);
        }
        presence.getObject().announce(documentId);
    }

    /**
     * Ends a session — <b>at most once</b>, whichever trigger arrives first.
     *
     * <p>The order below is the reviewable part, and it is: forget the rate-limit buckets, forget
     * the participant, count the session as closed, then tell the room. Announcing last is
     * deliberate — the broadcast reads the participant list, so doing it earlier would send a list
     * that still contains the person who has left.
     */
    public void end(String sessionId) {
        if (sessionId == null) {
            return;
        }
        boolean[] ended = new boolean[1];
        UUID[] document = new UUID[1];
        userBySession.computeIfPresent(sessionId, (id, user) -> {
            ended[0] = true;
            // Without this the bucket maps grow with every session since process start.
            rateLimiter.forget(sessionId);
            // Removed with the same key that added it, and only when this session is still the
            // holder: a reconnect can hand the SAME identifier out again (the counter wraps at
            // Integer.MAX_VALUE / 2), and a blind remove would then unregister the live session.
            Integer replicaId = replicaBySession.remove(sessionId);
            if (replicaId != null) {
                userByReplica.remove(replicaId, user);
            }
            document[0] = presence.getObject().recordLeave(sessionId);
            // Returning null REMOVES the key, and the removal is what makes a second call a no-op
            // and a concurrent joined find nothing.
            return null;
        });

        if (!ended[0]) {
            return;
        }
        metrics.sessionClosed();
        if (document[0] != null) {
            presence.getObject().announce(document[0]);
        }
    }

    /**
     * The reliable trigger: the WebSocket connection has closed.
     *
     * <p>Also where the close REASON is counted, because this is the only trigger that carries a
     * {@link CloseStatus} — the inbound {@code DISCONNECT} frame does not.
     */
    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        String reason = reasonOf(event.getCloseStatus());
        metrics.recordSessionClose(reason);

        // Loudly, because this is the only symptom of a problem the README currently records as
        // unresolved. Silent degradation is worse here than noise in the log.
        if (SLOW_CLIENT.equals(reason)) {
            log.warn("Session {} torn down: the client could not keep up with delivery and "
                    + "overflowed the send buffer (send-buffer-size-bytes, the transport decision). It will come "
                    + "back through reconnect + resync, but until then it RECEIVES NO broadcasts.",
                event.getSessionId());
        }
        end(event.getSessionId());
    }

    // ------------------------------------------------------------------
    // Close reasons — why sessions end, as a bounded metric tag
    // ------------------------------------------------------------------

    /**
     * The client did not receive fast enough and overflowed the send buffer —
     * {@code CloseStatus(4500)}.
     *
     * <h2>Why the reason is counted at all, given that sessions already are</h2>
     * {@code ws.sessions.active} and {@code ws.sessions.opened.total} say how many but not
     * <b>why</b> — and when diagnosing divergence that is the only interesting question. A session
     * closed by the user and a session torn down by the server because its send buffer overflowed
     * look identical in a counter, and mean something entirely different.
     *
     * <p>The README (the "Degradation point" section) describes an unresolved thread: above ~10
     * clients in one document convergence stops being repeatable, the server holds the complete set
     * of operations, and some clients fall permanently behind — <b>with no error in the log at
     * all</b>. The hypothesis is this close code: with N clients every operation goes out in N
     * frames, a slow client's send queue exceeds {@code send-buffer-size-bytes}, and Spring closes
     * its session. The client comes back through the reconnect path, so from outside it looks like
     * a hiccup rather than lost deliveries.
     *
     * <p>This metric settles it with a number instead of a guess. It fires at 25 clients and stays
     * silent at 10 -> hypothesis confirmed. Silent in both -> the cause is elsewhere and the search
     * continues. <b>Both outcomes are results</b>, and both beat "I do not know".
     *
     * <p>A tag rather than separate counters, because the DISTRIBUTION of close reasons is itself
     * information: a rise in {@code reason="server_error"} with {@code reason="normal"} unchanged is
     * a different problem from the reverse, and two independent counters would not show the
     * proportion.
     */
    static final String SLOW_CLIENT = "slow_client_buffer_overflow";

    /**
     * The close reason as a tag value.
     *
     * <p>The set of values is <b>closed</b>, and that is deliberate: a tag with an unbounded number
     * of values (the raw close code, say, or text from the client) blows up the metric's
     * cardinality in Prometheus. Codes outside this list land in a single {@code other} bucket.
     */
    static String reasonOf(CloseStatus status) {
        if (status == null) {
            return "unknown";
        }
        return switch (status.getCode()) {
            case 1000 -> "normal";
            case 1001 -> "going_away";
            case 1006 -> "abnormal";
            case 1011 -> "server_error";
            // 4500 — Spring closes a session this way when its send buffer exceeded the limit or
            // it exceeded send-time-limit. Both mean the same thing: the client cannot keep up.
            case 4500 -> SLOW_CLIENT;
            default -> "other";
        };
    }
}
