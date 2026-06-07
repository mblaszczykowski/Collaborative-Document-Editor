package dev.collabedit.app.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Odczyt identyfikatora dokumentu z nazwy kanału Redisa.
 *
 * <table>
 *   <caption>Test → failure mode</caption>
 *   <tr><th>Test</th><th>Co wyłapuje</th></tr>
 *   <tr><td>{@code channelRoundTrip}</td>
 *       <td>Rozjazd między budowaniem nazwy kanału a jej czytaniem. Skutek jest cichy
 *           i katastrofalny: instancja odrzuca WSZYSTKIE wiadomości klastrowe, bo żadnej
 *           nie potrafi przypisać do dokumentu. Nic się nie psuje natychmiast — operacje
 *           dociąga anti-entropy — ale propagacja między instancjami spada z milisekund
 *           do trzydziestu sekund i nie widać tego w żadnym logu.</td></tr>
 *   <tr><td>{@code malformedChannelIsRejected}</td>
 *       <td>Nazwa niepasująca do wzorca daje wyjątek na wątku nasłuchu Redisa albo —
 *           gorzej — losowy UUID, przez który wiadomość trafia do niewłaściwego dokumentu.</td></tr>
 * </table>
 */
class ClusterBusTest {

    @Test
    @DisplayName("nazwa kanału i jej odczyt są wzajemnie odwrotne")
    void channelRoundTrip() {
        UUID document = UUID.fromString("d7ccfdba-c8a1-44ea-9a7b-7f72910d88d8");

        assertThat(ClusterBus.documentIdOf(ClusterBus.channel(document)))
            .as("odczyt nie odtworzył identyfikatora, którym zbudowano nazwę kanału")
            .isEqualTo(document);
    }

    @Test
    @DisplayName("odczyt działa dla dowolnego identyfikatora, nie tylko dla przykładowego")
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
        "collabedit:doc:nie-uuid:ops",
        "collabedit:doc:d7ccfdba-c8a1-44ea-9a7b-7f72910d88d8",
        "inny:kanal:d7ccfdba-c8a1-44ea-9a7b-7f72910d88d8:ops",
        "collabedit:doc:d7ccfdba-c8a1-44ea-9a7b-7f72910d88d8:presence",
    })
    @DisplayName("nazwa spoza wzorca daje null, a nie wyjątek ani przypadkowy identyfikator")
    void malformedChannelIsRejected(String channel) {
        assertThat(ClusterBus.documentIdOf(channel)).isNull();
    }

    @Test
    @DisplayName("wzorzec subskrypcji pasuje do nazw, które sami budujemy")
    void subscriptionPatternMatchesOwnChannels() {
        String channel = ClusterBus.channel(UUID.randomUUID());
        String regex = ClusterBus.CHANNEL_PATTERN.replace("*", "[^:]+");

        assertThat(channel)
            .as("subskrybujemy wzorzec, pod który nasze własne kanały nie podpadają")
            .matches(regex);
    }
}
