package dev.collabedit.app.ws;

import dev.collabedit.app.auth.ReplicaIdAllocator;
import dev.collabedit.app.auth.TokenService;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Authentication and replica-identifier allocation — once, on the {@code CONNECT} frame.
 *
 * <p>This is where authentication hooks into the WebSocket. It is worth naming, because it is the
 * source of a common misunderstanding: the WebSocket handshake is an ordinary HTTP request, but
 * <b>the browser does not allow setting headers on it</b>. That is why the token travels in a
 * header of the STOMP {@code CONNECT} frame rather than in an HTTP header — and why this is a
 * channel interceptor rather than an HTTP filter.
 *
 * <p>The replica identifier is allocated here rather than on the client, because it has to be
 * globally unique — two replicas with the same identifier would generate colliding character
 * identifiers and silently diverge the document. That is the kind of bug that cannot be detected
 * after the fact, so the number comes from a single counter in Redis.
 */
@Configuration
public class StompConnectInterceptor implements WebSocketMessageBrokerConfigurer {

    private final TokenService tokens;
    private final ReplicaIdAllocator replicaIds;

    private final SessionLifecycle sessions;
    private final DocumentAccessInterceptor access;

    /**
     * Registered on the OUTBOUND channel. See {@link RevokedAccessFilter} for what it does and why
     * the inbound half alone left revocation half-implemented.
     */
    private final RevokedAccessFilter revoked;

    public StompConnectInterceptor(TokenService tokens, ReplicaIdAllocator replicaIds,
                                   SessionLifecycle sessions, DocumentAccessInterceptor access,
                                   RevokedAccessFilter revoked) {
        this.tokens = tokens;
        this.replicaIds = replicaIds;
        this.sessions = sessions;
        this.access = access;
        this.revoked = revoked;
    }

    /**
     * The order of the interceptors matters: <b>authentication first, then authorization</b>.
     *
     * <p>{@link DocumentAccessInterceptor} reads the identity from the session attributes, which
     * are only created while handling the {@code CONNECT} frame below. The reverse order would give
     * an authorization step seeing an empty identity — that is, denial for everyone or, with
     * careless code, a free pass for everyone.
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(sessionState(), access);
    }

    /**
     * There was no outbound configuration at all, and that is the shape of the hole CP12 left.
     *
     * <p>Authorizing only what comes IN answers "may this client act on the document" and leaves
     * "may this client keep watching it" unasked — so a revoked user's writes were refused while
     * every broadcast kept arriving. The full argument, including why the plan's own test name for
     * it never existed, is in {@link RevokedAccessFilter}.
     */
    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(revoked);
    }

    /**
     * Establishes a session's identity, and ends it.
     *
     * <p>The teardown itself is not here — it is {@link SessionLifecycle#end}, which is also
     * reached from {@code SessionDisconnectEvent}. This frame is the PROMPT trigger of the two; the
     * event is the reliable one, for a client that vanishes without saying anything. Both go to the
     * same idempotent method, so the order between the two no longer matters.
     */
    private ChannelInterceptor sessionState() {
        return new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor =
                    MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor == null) {
                    return message;
                }
                if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                    authenticate(accessor);
                    sessions.begin(accessor.getSessionId(),
                        SessionAttributes.userId(accessor));
                } else if (StompCommand.DISCONNECT.equals(accessor.getCommand())) {
                    sessions.end(accessor.getSessionId());
                }
                return message;
            }
        };
    }

    private void authenticate(StompHeaderAccessor accessor) {
        List<String> presented = accessor.getNativeHeader("token");
        String token = presented == null || presented.isEmpty() ? null : presented.getFirst();

        TokenService.Principal principal = tokens.verify(token)
            .orElseThrow(() -> new IllegalArgumentException(
                "no valid token in the CONNECT frame"));

        Map<String, Object> attributes = accessor.getSessionAttributes();
        if (attributes == null) {
            throw new IllegalStateException("session without attributes");
        }
        attributes.put(SessionAttributes.USER_ID, principal.userId());
        attributes.put(SessionAttributes.DISPLAY_NAME, principal.displayName());
        attributes.put(SessionAttributes.COLOR, principal.color());
        attributes.put(SessionAttributes.REPLICA_ID, replicaIds.next());
    }
}
