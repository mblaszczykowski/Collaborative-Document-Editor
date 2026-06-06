package dev.collabedit.app.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * Liczy, <b>dlaczego</b> kończą się sesje WebSocket.
 *
 * <h2>Po co, skoro sesje i tak są liczone</h2>
 * Istniejące {@code ws.sessions.active} i {@code ws.sessions.opened.total} mówią ile,
 * ale nie mówią <b>dlaczego</b> — a przy diagnozie rozjazdu to jest jedyne interesujące
 * pytanie. Sesja zamknięta przez użytkownika i sesja zerwana przez serwer z powodu
 * przepełnienia bufora wysyłkowego wyglądają w liczniku identycznie, a znaczą coś
 * zupełnie innego.
 *
 * <h2>Otwarte pytanie, na które ta metryka ma odpowiedzieć</h2>
 * README (sekcja „Punkt degradacji") opisuje niezamknięty wątek: powyżej ~10 klientów
 * w jednym dokumencie zbieżność przestaje być powtarzalna, serwer ma komplet operacji,
 * a część klientów zostaje trwale w tyle — <b>bez żadnego błędu w logu</b>.
 *
 * <p>Hipoteza jest taka, że to jest {@link CloseStatus#SESSION_NOT_RELIABLE}: przy N klientach
 * każda operacja rozchodzi się w N ramkach, kolejka wysyłkowa wolnego klienta przekracza
 * {@code send-buffer-size-bytes} i Spring zamyka mu sesję. Klient wraca ścieżką ponownego
 * połączenia, więc z zewnątrz wygląda to na „chwilowy przestój", a nie na utratę dostaw.
 *
 * <p>Ta metryka rozstrzyga to liczbą zamiast domysłem. Zapala się przy 25 klientach
 * i milczy przy 10 → hipoteza potwierdzona. Milczy w obu → przyczyna jest gdzie indziej
 * i trzeba szukać dalej. <b>Oba wyniki są wynikami</b> i oba są lepsze niż obecne „nie wiem".
 *
 * <p>Powód, dla którego jest to <i>tag</i>, a nie osobny licznik: rozkład powodów zamknięcia
 * jest sam w sobie informacją. Nagły wzrost {@code reason="server_error"} przy niezmienionym
 * {@code reason="normal"} to inny problem niż odwrotnie, a dwa niezależne liczniki nie
 * pozwoliłyby zobaczyć proporcji.
 */
@Component
public class SessionCloseListener {

    private static final Logger log = LoggerFactory.getLogger(SessionCloseListener.class);

    private final DocumentMetrics metrics;

    public SessionCloseListener(DocumentMetrics metrics) {
        this.metrics = metrics;
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        CloseStatus status = event.getCloseStatus();
        String reason = reasonOf(status);

        metrics.recordSessionClose(reason);

        // Głośno, bo to jest jedyny objaw problemu, który dziś jest w README opisany jako
        // nierozstrzygnięty. Cicha degradacja jest tu gorsza od hałasu w logu.
        if (SLOW_CLIENT.equals(reason)) {
            log.warn("Sesja {} zerwana: klient nie nadążał z odbiorem i przepełnił bufor "
                    + "wysyłkowy (send-buffer-size-bytes, ADR-0002). Klient wróci ścieżką "
                    + "reconnect + resync, ale do tego czasu NIE DOSTAJE rozgłoszeń.",
                event.getSessionId());
        }
    }

    /** Klient nie odbierał dość szybko i przepełnił bufor wysyłkowy — {@code CloseStatus(4500)}. */
    static final String SLOW_CLIENT = "slow_client_buffer_overflow";

    /**
     * Powód zamknięcia jako wartość taga.
     *
     * <p>Zbiór wartości jest <b>domknięty</b> i to jest celowe: tag o nieograniczonej
     * liczbie wartości (np. surowy kod zamknięcia albo tekst od klienta) wysadza kardynalność
     * metryki w Prometheusie. Kody spoza tej listy lądują w jednym koszu {@code other}.
     */
    static String reasonOf(CloseStatus status) {
        if (status == null) {
            return "unknown";
        }
        return switch (status.getCode()) {
            case 1000 -> "normal";
            case 1001 -> "going_away";
            case 1006 -> "abnormal";
            case 1011 -> "server_error";
            // 4500 — Spring zamyka tak sesję, której bufor wysyłkowy przekroczył limit
            // albo która przekroczyła send-time-limit. Oba znaczą to samo: klient nie nadąża.
            case 4500 -> SLOW_CLIENT;
            default -> "other";
        };
    }
}
