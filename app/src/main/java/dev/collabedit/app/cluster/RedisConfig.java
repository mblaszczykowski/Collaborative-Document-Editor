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
 * w pamięci, jest tani.
 *
 * <p><b>Ale tylko dlatego, że dzieje się po nazwie kanału.</b> Do CP11 uzasadnienie brzmiało
 * „odsiew jest trywialny" i było prawdziwe wyłącznie w połowie: samo sprawdzenie owszem,
 * ale poprzedzało je sparsowanie CAŁEGO ładunku JSON, bo identyfikator dokumentu czytano
 * z treści wiadomości. Przy jednym dokumencie nie miało to znaczenia. Przy wielu oznaczałoby,
 * że każda instancja parsuje ruch całego klastra, żeby w większości przypadków wyrzucić wynik.
 *
 * <p>Identyfikator dokumentu jest w nazwie kanału ({@code collabedit:doc:{uuid}:ops}), więc
 * odsiew da się zrobić przed odczytaniem choćby bajtu treści — i tak robi to {@link ClusterBus}.
 * Dopiero teraz to uzasadnienie jest prawdziwe w całości.
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
