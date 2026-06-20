package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.app.cluster.PresenceBus;
import dev.collabedit.app.ws.PresenceService;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Presence across instances (CP26).
 *
 * <p>The participant list was instance-local, which the javadoc said plainly and the interface did
 * not: somebody connected to another instance simply did not appear, and a list that is quietly
 * short looks exactly like a document nobody else is in.
 *
 * <p>These tests use a <b>real Redis announcement from a made-up instance</b> rather than a second
 * Spring context. That is deliberate: the thing worth pinning is the merge and the expiry, and a
 * second context would spend fifteen seconds booting to test the same two rules while adding the
 * flakiness {@code ClusterChaosIT} already documents.
 *
 * <table>
 *   <caption>Test → failure mode</caption>
 *   <tr><th>Test</th><th>What it catches</th></tr>
 *   <tr><td>{@code somebodyOnAnotherInstanceIsVisible}</td>
 *       <td>The debt this checkpoint exists to pay.</td></tr>
 *   <tr><td>{@code anInstanceThatGoesQuietDisappears}</td>
 *       <td>The opposite failure, and the worse one: a crashed instance leaving ghosts in
 *           everybody's list forever, because the only party who could say they left is the one
 *           that died.</td></tr>
 *   <tr><td>{@code ourOwnAnnouncementIsIgnored}</td>
 *       <td>Every participant appearing twice — pub/sub delivers to the sender as well.</td></tr>
 * </table>
 */
class PresenceClusterIT extends IntegrationTestBase {

    @Autowired
    PresenceService presence;
    @Autowired
    PresenceBus bus;
    @Autowired
    StringRedisTemplate redis;

    /** An announcement as another instance would send it. */
    private void announce(String instanceId, UUID documentId, String name, int replicaId) {
        redis.convertAndSend(PresenceBus.CHANNEL, """
            {"i":"%s","d":"%s","p":[{"replicaId":%d,"userId":"u-%s","displayName":"%s",
             "color":"#2E8B6B","cursorReplicaId":null,"cursorLamport":null,
             "anchorReplicaId":null,"anchorLamport":null,"typing":false,"online":true}]}
            """.formatted(instanceId, documentId, replicaId, name, name));
    }

    private void awaitParticipants(UUID documentId, int expected) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (presence.everybody(documentId).size() == expected) {
                return;
            }
            Thread.sleep(50);
        }
        assertThat(presence.everybody(documentId))
            .as("waited %s for %d participants", Duration.ofSeconds(5), expected)
            .hasSize(expected);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("somebody connected to another instance appears in the list")
    void somebodyOnAnotherInstanceIsVisible() throws Exception {
        UUID document = UUID.randomUUID();

        announce("other-instance", document, "Zdalna", 4242);
        awaitParticipants(document, 1);

        List<dev.collabedit.app.ws.Messages.PresenceBroadcast> everybody =
            presence.everybody(document);
        assertThat(everybody.getFirst().displayName()).isEqualTo("Zdalna");
        assertThat(everybody.getFirst().replicaId()).isEqualTo(4242);
    }

    @Test
    @DisplayName("an instance that goes quiet stops being believed")
    void anInstanceThatGoesQuietDisappears() throws Exception {
        UUID document = UUID.randomUUID();

        announce("dying-instance", document, "Duch", 4343);
        awaitParticipants(document, 1);

        // No further announcements. Nothing repairs this and nothing needs to: the entry expires,
        // which is the only way a crashed instance's participants can ever leave a list — the
        // party who could have said so is the one that died.
        Thread.sleep(3_500);

        assertThat(presence.everybody(document))
            .as("a ghost that never leaves is worse than a missing participant: it is a claim "
                + "the system keeps making after it stopped being true")
            .isEmpty();
    }

    @Test
    @DisplayName("our own announcement does not come back as a second participant")
    void ourOwnAnnouncementIsIgnored() throws Exception {
        UUID document = UUID.randomUUID();

        announce(bus.instanceId(), document, "Ja", 4444);
        Thread.sleep(500);

        assertThat(presence.everybody(document))
            .as("Redis pub/sub delivers to the sender too, so without the filter every "
                + "participant would be listed twice on their own instance")
            .isEmpty();
    }
}
