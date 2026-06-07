package dev.collabedit.app.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;

/**
 * Mapowanie kodu zamknięcia sesji na wartość taga metryki.
 *
 * <table>
 *   <caption>Test → failure mode</caption>
 *   <tr><th>Test</th><th>Co wyłapuje</th></tr>
 *   <tr><td>{@code bufferOverflowIsRecognised}</td>
 *       <td>Kod 4500 wpada do kosza {@code other}. Metryka istnieje, panel jest,
 *           wykres płaski — i wygląda to jak dowód, że problemu nie ma, podczas gdy
 *           znaczy tylko, że go nie mierzymy. To jest najgorszy możliwy tryb awarii
 *           metryki diagnostycznej.</td></tr>
 *   <tr><td>{@code unknownCodesCollapseIntoOneBucket}</td>
 *       <td>Kod zamknięcia trafia do taga wprost. Klient może zamknąć połączenie dowolnym
 *           kodem z zakresu 4000–4999, więc byłaby to kardynalność sterowana z zewnątrz —
 *           czyli sposób na wysadzenie Prometheusa jednym złośliwym klientem.</td></tr>
 *   <tr><td>{@code missingStatusDoesNotThrow}</td>
 *       <td>Zdarzenie bez statusu wywala listener. Wyjątek w obsłudze zdarzenia Springa
 *           przewraca całe rozłączanie sesji, a nie tylko metrykę.</td></tr>
 * </table>
 */
class SessionCloseListenerTest {

    @Test
    @DisplayName("przepełnienie bufora wysyłkowego ma własną wartość taga, nie wpada do 'other'")
    void bufferOverflowIsRecognised() {
        assertThat(SessionCloseListener.reasonOf(CloseStatus.SESSION_NOT_RELIABLE))
            .isEqualTo(SessionCloseListener.SLOW_CLIENT);

        // Ten sam kod zapisany wprost — gdyby Spring zmienił stałą, chcemy o tym wiedzieć
        // od testu, a nie od płaskiego wykresu.
        assertThat(CloseStatus.SESSION_NOT_RELIABLE.getCode()).isEqualTo(4500);
    }

    @Test
    @DisplayName("zamknięcia rozpoznane mają rozłączne wartości taga")
    void knownCodesAreDistinct() {
        assertThat(SessionCloseListener.reasonOf(CloseStatus.NORMAL)).isEqualTo("normal");
        assertThat(SessionCloseListener.reasonOf(CloseStatus.GOING_AWAY)).isEqualTo("going_away");
        assertThat(SessionCloseListener.reasonOf(CloseStatus.NO_CLOSE_FRAME)).isEqualTo("abnormal");
        assertThat(SessionCloseListener.reasonOf(CloseStatus.SERVER_ERROR)).isEqualTo("server_error");
    }

    @Test
    @DisplayName("nieznane kody lądują w jednym koszu — kardynalność nie może zależeć od klienta")
    void unknownCodesCollapseIntoOneBucket() {
        assertThat(SessionCloseListener.reasonOf(new CloseStatus(4001))).isEqualTo("other");
        assertThat(SessionCloseListener.reasonOf(new CloseStatus(4999))).isEqualTo("other");
        assertThat(SessionCloseListener.reasonOf(new CloseStatus(1013))).isEqualTo("other");
    }

    @Test
    @DisplayName("brak statusu daje wartość zastępczą zamiast wyjątku")
    void missingStatusDoesNotThrow() {
        assertThat(SessionCloseListener.reasonOf(null)).isEqualTo("unknown");
    }
}
