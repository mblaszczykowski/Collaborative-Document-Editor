package dev.collabedit.app.ws;

import dev.collabedit.app.config.CollabEditProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * Konfiguracja transportu — każda wartość liczbowa pochodzi z ADR-0002 i jest tam uzasadniona.
 * Zmiana którejkolwiek z nich bez aktualizacji tamtego dokumentu sprawia, że uzasadnienie
 * przestaje odpowiadać rzeczywistości, co jest gorsze niż jego brak.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final CollabEditProperties properties;

    public WebSocketConfig(CollabEditProperties properties) {
        this.properties = properties;
    }

    /**
     * Osobny scheduler dla heartbeatów STOMP.
     *
     * <p>Bez własnego schedulera broker w pamięci <b>po cichu nie wysyła heartbeatów</b> —
     * konfiguracja jest przyjmowana, ale nie działa. Efektem byłoby zrywanie połączeń przez
     * pośredniki po ich własnym idle timeout, czyli dokładnie to, przed czym heartbeat ma
     * chronić (ADR-0002). Jeden z tych przypadków, gdzie brak konfiguracji nie powoduje
     * błędu, tylko cichą utratę funkcji.
     *
     * <p><b>This bean also used to run every {@code @Scheduled} method in the application, and
     * nothing said so.</b> Declaring any {@code TaskScheduler} makes Spring Boot's
     * {@code TaskSchedulingAutoConfiguration} back off — it is annotated
     * {@code @ConditionalOnMissingBean({TaskScheduler.class, ScheduledExecutorService.class})}
     * — after which {@code ScheduledAnnotationBeanPostProcessor} binds to whatever single
     * {@code TaskScheduler} it can find. That was this one, with a pool of exactly one thread,
     * so the 10 ms broadcast, the 50 ms flush, document snapshots and the anti-entropy sweep
     * all queued behind each other and behind heartbeat emission.
     *
     * <p>{@link dev.collabedit.app.config.SchedulingConfig} now declares the pools that the
     * periodic jobs name explicitly, so this scheduler serves heartbeats and nothing else.
     * {@code SchedulingWiringTest} fails the build if that stops being true.
     */
    @Bean
    public TaskScheduler stompHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("stomp-heartbeat-");
        scheduler.initialize();
        return scheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
            // Bez SockJS — świadomie (ADR-0002). Fallbacki (xhr-streaming, jsonp) to ścieżki
            // transportowe, których nie testujemy, czyli nieprzetestowany kod w miejscu
            // krytycznym. Ceną jest brak połączenia zza proxy blokującego Upgrade.
            .setAllowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        long interval = properties.websocket().heartbeatIntervalMs();
        registry.enableSimpleBroker("/topic", "/queue")
            .setHeartbeatValue(new long[] {interval, interval})
            .setTaskScheduler(stompHeartbeatScheduler());
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration
            .setMessageSizeLimit(properties.websocket().maxMessageSizeBytes())
            // Po przepełnieniu bufora sesja jest zamykana, a klient przechodzi ścieżką
            // ponownego połączenia i resynchronizacji. Kontrolowana degradacja jednego
            // wolnego klienta zamiast zatoru na całym dokumencie (ADR-0002).
            .setSendBufferSizeLimit(properties.websocket().sendBufferSizeBytes())
            .setSendTimeLimit((int) properties.websocket().sendTimeLimitMs());
    }
}
