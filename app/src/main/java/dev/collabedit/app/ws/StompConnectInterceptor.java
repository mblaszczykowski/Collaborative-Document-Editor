package dev.collabedit.app.ws;

import dev.collabedit.app.auth.ReplicaIdAllocator;
import dev.collabedit.app.auth.TokenService;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
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
 * Uwierzytelnienie i przydział identyfikatora repliki — jednorazowo, przy ramce
 * {@code CONNECT}.
 *
 * <p>To jest miejsce, w którym uwierzytelnienie wpina się w WebSocket. Warto je nazwać,
 * bo jest źródłem częstego nieporozumienia: uścisk dłoni WebSocketa to zwykłe żądanie HTTP,
 * ale <b>przeglądarka nie pozwala ustawić na nim nagłówków</b>. Dlatego token wędruje
 * w nagłówku ramki STOMP {@code CONNECT}, a nie w nagłówku HTTP — i dlatego interceptor
 * kanału, a nie filtr HTTP.
 *
 * <p>Identyfikator repliki przydzielamy tutaj, a nie po stronie klienta, bo musi być
 * globalnie unikalny — dwie repliki o tym samym identyfikatorze generowałyby kolidujące
 * identyfikatory znaków i cicho rozspójniły dokument. To jest ten rodzaj błędu, którego
 * nie da się wykryć po fakcie, więc źródłem numeru jest jeden licznik w Redisie.
 */
@Configuration
public class StompConnectInterceptor implements WebSocketMessageBrokerConfigurer {

    private final TokenService tokens;
    private final ReplicaIdAllocator replicaIds;

    /**
     * {@link PresenceService} wstrzykiwany <b>leniwie</b>, bo inaczej powstaje cykl:
     * ten konfigurator jest potrzebny do zbudowania infrastruktury WebSocketa, a
     * {@code PresenceService} potrzebuje {@code SimpMessagingTemplate}, który z tej
     * infrastruktury pochodzi. Spring wykrywa to jako
     * {@code BeanCurrentlyInCreationException} przy starcie.
     *
     * <p>{@code ObjectProvider} zamiast {@code @Lazy}: jest jawny w sygnaturze, więc
     * następna osoba widzi, że to jest przerwanie cyklu, a nie przeoczenie.
     */
    private final ObjectProvider<PresenceService> presence;

    public StompConnectInterceptor(TokenService tokens, ReplicaIdAllocator replicaIds,
                                   ObjectProvider<PresenceService> presence) {
        this.tokens = tokens;
        this.replicaIds = replicaIds;
        this.presence = presence;
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor =
                    MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor == null) {
                    return message;
                }
                if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                    authenticate(accessor);
                } else if (StompCommand.DISCONNECT.equals(accessor.getCommand())) {
                    presence.getObject().leave(accessor.getSessionId());
                }
                return message;
            }
        });
    }

    private void authenticate(StompHeaderAccessor accessor) {
        List<String> presented = accessor.getNativeHeader("token");
        String token = presented == null || presented.isEmpty() ? null : presented.getFirst();

        TokenService.Principal principal = tokens.verify(token)
            .orElseThrow(() -> new IllegalArgumentException(
                "brak ważnego tokenu w ramce CONNECT"));

        Map<String, Object> attributes = accessor.getSessionAttributes();
        if (attributes == null) {
            throw new IllegalStateException("sesja bez atrybutów");
        }
        attributes.put(SessionAttributes.USER_ID, principal.userId());
        attributes.put(SessionAttributes.DISPLAY_NAME, principal.displayName());
        attributes.put(SessionAttributes.COLOR, principal.color());
        attributes.put(SessionAttributes.REPLICA_ID, replicaIds.next());
    }
}
