package dev.collabedit.app.cluster;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Listening for operations from the other instances (ADR-0004).
 *
 * <p>The subscription is a pattern subscription (one pattern for all documents) rather than one
 * channel per document. The reason: subscribing and unsubscribing on every document open and close
 * would generate control traffic proportional to user activity, whereas filtering out messages
 * about documents this instance does not hold in memory is cheap.
 *
 * <p><b>But only because the filtering happens on the channel name.</b> Up to CP11 the
 * justification read "the filtering is trivial", and it was true only by half: the check itself
 * was, but it was preceded by parsing the ENTIRE JSON payload, because the document identifier was
 * read from the message body. With one document that did not matter. With many it would mean every
 * instance parsing the whole cluster's traffic in order to discard the result most of the time.
 *
 * <p>The document identifier is in the channel name ({@code collabedit:doc:{uuid}:ops}), so the
 * filtering can happen before a single byte of the body is read — and that is what
 * {@link ClusterBus} does. Only now is that justification true in full.
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
