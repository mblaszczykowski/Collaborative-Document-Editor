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
 * Transport configuration — every numeric value comes from the transport decision and is justified there.
 * Changing any of them without updating that document makes the justification stop matching
 * reality, which is worse than having none.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final CollabEditProperties properties;

    public WebSocketConfig(CollabEditProperties properties) {
        this.properties = properties;
    }

    /**
     * A dedicated scheduler for STOMP heartbeats.
     *
     * <p>Without its own scheduler the in-memory broker <b>silently sends no heartbeats</b> — the
     * configuration is accepted but does not work. The effect would be intermediaries tearing
     * connections down after their own idle timeout, which is exactly what the heartbeat exists to
     * prevent. One of those cases where missing configuration causes no error, only the
     * silent loss of a feature.
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
            // No SockJS — deliberately. The fallbacks (xhr-streaming, jsonp) are
            // transport paths we do not test, that is, untested code in a critical place. The price
            // is no connection from behind a proxy that blocks Upgrade.
            //
            // The patterns come from configuration and default to localhost only. Pinning them
            // here is what made a hosted deployment fail at the handshake: the browser sends
            // the deployed origin and the server compares it against localhost.
            .setAllowedOriginPatterns(
                properties.websocket().allowedOriginPatterns().toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        long interval = properties.websocket().heartbeatIntervalMs();
        registry.enableSimpleBroker("/topic", "/queue")
            .setHeartbeatValue(new long[] {interval, interval})
            .setTaskScheduler(stompHeartbeatScheduler());
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");

        // THE SECOND HALF OF THE BROADCAST-BEFORE-ACKNOWLEDGEMENT GUARANTEE.
        //
        // DocumentReplica.drainForFlush takes both buffers under one lock so an acknowledgement
        // cannot be produced before the broadcast of the same operation, and SchedulingConfig
        // keeps the flow pool single-threaded so no second thread can send for the same document.
        // Both of those are about the SUBMISSION order. Neither survives what happens next:
        // Spring's clientOutboundChannel is executor-backed, so frames submitted in order are
        // dispatched by a thread pool and can reach the socket in a different one.
        //
        // If the acknowledgement wins that race, the client's gap detector fires — it keys on
        // being acknowledged an operation it does not hold — and asks for a full resynchronisation
        // from zero. That is the exact cost the batching design exists to avoid, triggered on a
        // completely healthy system.
        //
        // The price is real and worth stating: this serialises delivery per session through
        // OrderedMessageChannelDecorator. Per session, not globally — different documents and
        // different clients are unaffected — and the alternative is an invariant that two other
        // files go to considerable lengths to establish and that nothing actually holds.
        registry.setPreservePublishOrder(true);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration
            .setMessageSizeLimit(properties.websocket().maxMessageSizeBytes())
            // Once the buffer overflows the session is closed and the client takes the reconnect
            // and resynchronisation path. Controlled degradation of one slow client instead of a
            // jam across the whole document.
            .setSendBufferSizeLimit(properties.websocket().sendBufferSizeBytes())
            .setSendTimeLimit((int) properties.websocket().sendTimeLimitMs());
    }
}
