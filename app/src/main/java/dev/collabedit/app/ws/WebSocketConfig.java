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

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final CollabEditProperties properties;

    public WebSocketConfig(CollabEditProperties properties) {
        this.properties = properties;
    }

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
        // SUBSCRIBE must finish before a following SEND asks the application to reply to it.
        // Ordering is per session; separate collaborators still run concurrently.
        registry.setPreserveReceiveOrder(true);
        registry.addEndpoint("/ws")
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

        registry.setPreservePublishOrder(true);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration
            .setMessageSizeLimit(properties.websocket().maxMessageSizeBytes())
            .setSendBufferSizeLimit(properties.websocket().sendBufferSizeBytes())
            .setSendTimeLimit((int) properties.websocket().sendTimeLimitMs());
    }
}
