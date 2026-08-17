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

@Configuration
public class StompConnectInterceptor implements WebSocketMessageBrokerConfigurer {
    private final TokenService tokens;
    private final ReplicaIdAllocator replicaIds;

    private final SessionLifecycle sessions;
    private final DocumentAccessInterceptor access;

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

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(sessionState(), access);
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(revoked);
    }

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
