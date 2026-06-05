package dev.collabedit.app.cluster;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Nasłuch operacji z pozostałych instancji (ADR-0004).
 *
 * <p>Subskrypcja jest wzorcowa (jeden wzorzec na wszystkie dokumenty), a nie po jednym
 * kanale na dokument. Powód: subskrybowanie i odsubskrybowanie kanału przy każdym otwarciu
 * i zamknięciu dokumentu generowałoby ruch sterujący proporcjonalny do aktywności
 * użytkowników, a odsiew wiadomości dotyczących dokumentów, których ta instancja nie trzyma
 * w pamięci, jest i tak trywialny i dzieje się w {@link ClusterBus}.
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisMessageListenerContainer clusterListenerContainer(
        RedisConnectionFactory connectionFactory, ClusterBus bus) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(bus, new PatternTopic(ClusterBus.CHANNEL_PATTERN));
        return container;
    }
}
