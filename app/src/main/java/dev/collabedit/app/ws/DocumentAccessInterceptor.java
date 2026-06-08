package dev.collabedit.app.ws;

import dev.collabedit.app.auth.PermissionService;
import dev.collabedit.app.auth.Role;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Autoryzacja <b>każdej ramki</b> STOMP wchodzącej do systemu (CP12).
 *
 * <h2>Dlaczego to jest najważniejszy plik tego checkpointu</h2>
 * Do CP12 autoryzacji nie było w ogóle: {@link StompConnectInterceptor} sprawdzał token
 * przy {@code CONNECT} i na tym kończyła się kontrola dostępu. Przy jednym wspólnym
 * dokumencie było to spójne uproszczenie — model brzmiał „każdy zalogowany może wszystko"
 * i tak też się zachowywał.
 *
 * <p>Od CP11 dokumentów jest wiele, a identyfikator dokumentu jest częścią destynacji
 * podawanej przez klienta. Bez tego interceptora dowolny zalogowany użytkownik mógłby
 * zasubskrybować {@code /topic/doc/{cudze-id}/ops} i czytać cudzy dokument na żywo albo
 * wysłać własną operację na {@code /app/doc/{cudze-id}/op}. Uwierzytelnienie bez
 * autoryzacji nie chroni przed niczym poza anonimowością.
 *
 * <h2>Dlaczego to nie może być tylko sprawdzenie dokumentu</h2>
 * Rola {@link Role#COMMENTER} wolno czytać i komentować, ale nie zmieniać tekstu.
 * Komentarze (CP14) idą tym samym kanałem operacji co edycja, więc rozróżnienie „wolno
 * pisać" od „wolno komentować" <b>nie istnieje na poziomie destynacji</b> — musi zejść
 * do typu operacji. Mapowanie roli na dozwolone typy jest tu, w jednym miejscu, właśnie
 * po to, żeby dołożenie typu operacji w CP13 i CP14 było wpisem w tablicy, a nie
 * polowaniem na rozsiane po kodzie warunki.
 */
@Component
public class DocumentAccessInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(DocumentAccessInterceptor.class);

    private final PermissionService permissions;

    public DocumentAccessInterceptor(PermissionService permissions) {
        this.permissions = permissions;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
            MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        StompCommand command = accessor.getCommand();
        if (command != StompCommand.SUBSCRIBE && command != StompCommand.SEND) {
            return message;
        }

        String destination = accessor.getDestination();
        UUID documentId = documentIdOf(destination);
        if (documentId == null) {
            // Destynacje niezwiązane z dokumentem — kolejki własne użytkownika
            // (/user/queue/**). Są z definicji prywatne: broker rozsyła je wyłącznie
            // do sesji, która je zasubskrybowała.
            return message;
        }

        String userId = SessionAttributes.userId(accessor);
        Role role = permissions.roleOf(userId, documentId).orElse(null);

        if (role == null) {
            return deny(command, destination, userId, "brak jakiegokolwiek dostępu");
        }
        if (command == StompCommand.SEND && !allowedToSend(role, destination)) {
            return deny(command, destination, userId, "rola " + role.wire() + " nie pozwala na tę operację");
        }
        return message;
    }

    /**
     * Czy rola pozwala na wysłanie na tę destynację.
     *
     * <p>Odczyt (SUBSCRIBE) wystarczy mieć jakąkolwiek rolę — najniższa z nich, {@code viewer},
     * z definicji czyta. Wysyłanie rozstrzyga się per destynacja.
     */
    private static boolean allowedToSend(Role role, String destination) {
        if (destination.endsWith("/op")) {
            // Operacja CRDT zmieniająca treść. Od CP14 ten warunek rozszerzy się o typ
            // operacji: komentujący może wysłać znacznik komentarza, ale nie wstawienie tekstu.
            return role.canWriteText();
        }
        if (destination.endsWith("/presence")
            || destination.endsWith("/sync")
            || destination.endsWith("/progress")
            || destination.endsWith("/join")) {
            // Obecność, resynchronizacja i raport postępu to czynności CZYTAJĄCEGO.
            // Kursor obserwatora ma być widoczny dla pozostałych — inaczej „ktoś tu jest"
            // działa wyłącznie dla osób z prawem zapisu, co jest mylące.
            return true;
        }
        // Nieznana destynacja pod /app/doc/** — odmawiamy. Domyślną odpowiedzią przy
        // rozszerzaniu protokołu ma być „nie wolno", żeby nowy kanał trzeba było
        // świadomie dopuścić, zamiast odkrywać, że był dopuszczony od początku.
        return false;
    }

    private Message<?> deny(StompCommand command, String destination, String userId, String reason) {
        log.warn("Odmowa: {} {} dla użytkownika {} — {}", command, destination, userId, reason);
        // Zwrócenie null zatrzymuje wiadomość na kanale. Klient nie dostaje potwierdzenia
        // subskrypcji ani rozgłoszeń, czyli z jego punktu widzenia dokument nie istnieje.
        // Świadomie NIE odsyłamy szczegółów: informacja „ten dokument istnieje, ale nie masz
        // do niego dostępu" jest sama w sobie wyciekiem.
        return null;
    }

    /**
     * Wyciąga identyfikator dokumentu z destynacji {@code /topic/doc/{uuid}/…}
     * albo {@code /app/doc/{uuid}/…}.
     *
     * @return {@code null}, gdy destynacja nie dotyczy dokumentu
     */
    static UUID documentIdOf(String destination) {
        if (destination == null) {
            return null;
        }
        int marker = destination.indexOf("/doc/");
        if (marker < 0) {
            return null;
        }
        int start = marker + "/doc/".length();
        int end = destination.indexOf('/', start);
        String candidate = end < 0 ? destination.substring(start) : destination.substring(start, end);
        try {
            return UUID.fromString(candidate);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
