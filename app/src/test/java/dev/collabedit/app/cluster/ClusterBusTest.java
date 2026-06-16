package dev.collabedit.app.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Reading a document identifier out of a Redis channel name.
 *
 * <table>
 *   <caption>Test -> failure mode</caption>
 *   <tr><th>Test</th><th>What it catches</th></tr>
 *   <tr><td>{@code channelRoundTrip}</td>
 *       <td>Drift between building a channel name and parsing it. The effect is silent and
 *           catastrophic: the instance discards EVERY cluster message, because it cannot assign
 *           any of them to a document. Nothing breaks immediately — anti-entropy pulls the
 *           operations in — but propagation between instances drops from milliseconds to thirty
 *           seconds, and no log shows it.</td></tr>
 *   <tr><td>{@code malformedChannelIsRejected}</td>
 *       <td>A name not matching the pattern throws on the Redis listener thread or — worse —
 *           yields a random UUID that sends the message to the wrong document.</td></tr>
 * </table>
 */
class ClusterBusTest {

    @Test
    @DisplayName("building a channel name and parsing it are mutually inverse")
    void channelRoundTrip() {
        UUID document = UUID.fromString("d7ccfdba-c8a1-44ea-9a7b-7f72910d88d8");

        assertThat(ClusterBus.documentIdOf(ClusterBus.channel(document)))
            .as("parsing did not recover the identifier the channel name was built from")
            .isEqualTo(document);
    }

    @Test
    @DisplayName("parsing works for any identifier, not just the sample one")
    void channelRoundTripForRandomIds() {
        for (int i = 0; i < 100; i++) {
            UUID document = UUID.randomUUID();
            assertThat(ClusterBus.documentIdOf(ClusterBus.channel(document))).isEqualTo(document);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "collabedit:doc::ops",
        "collabedit:doc:not-a-uuid:ops",
        "collabedit:doc:d7ccfdba-c8a1-44ea-9a7b-7f72910d88d8",
        "other:channel:d7ccfdba-c8a1-44ea-9a7b-7f72910d88d8:ops",
        "collabedit:doc:d7ccfdba-c8a1-44ea-9a7b-7f72910d88d8:presence",
    })
    @DisplayName("a name outside the pattern gives null, not an exception or a random identifier")
    void malformedChannelIsRejected(String channel) {
        assertThat(ClusterBus.documentIdOf(channel)).isNull();
    }

    @Test
    @DisplayName("the subscription pattern matches the names we build ourselves")
    void subscriptionPatternMatchesOwnChannels() {
        String channel = ClusterBus.channel(UUID.randomUUID());
        String regex = ClusterBus.CHANNEL_PATTERN.replace("*", "[^:]+");

        assertThat(channel)
            .as("we subscribe to a pattern our own channels do not match")
            .matches(regex);
    }
}
