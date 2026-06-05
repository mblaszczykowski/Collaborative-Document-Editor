package dev.collabedit.app;

import com.fasterxml.jackson.databind.JsonNode;
import dev.collabedit.crdt.Op;
import dev.collabedit.crdt.OpCodec;
import dev.collabedit.crdt.RgaDocument;
import dev.collabedit.crdt.TreapBlockList;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * Klient testowy — przeglądarka udawana na tyle wiernie, żeby test był o czymś.
 *
 * <p>Kluczowe: klient trzyma <b>własną replikę CRDT</b> i stosuje do niej operacje odebrane
 * z serwera, dokładnie tak jak zrobi to frontend. Bez tego test sprawdzałby jedynie, czy
 * serwer coś rozsyła — a pytanie brzmi, czy <b>wszystkie strony dochodzą do tego samego
 * tekstu</b>, co jest tezą całego projektu.
 */
public class TestStompClient implements AutoCloseable {

    private final StompSession session;
    private final UUID documentId;
    private final RgaDocument document;

    private final List<Op> received = new CopyOnWriteArrayList<>();
    private final AtomicLong lastAckSeq = new AtomicLong(0);

    public TestStompClient(int port, String token, UUID documentId, int replicaId) throws Exception {
        this.documentId = documentId;
        this.document = new RgaDocument(replicaId, new TreapBlockList());

        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());

        StompHeaders connectHeaders = new StompHeaders();
        // Token w nagłówku ramki CONNECT, nie w nagłówku HTTP — przeglądarka nie potrafi
        // ustawić nagłówków na uścisku dłoni WebSocketa (patrz StompConnectInterceptor).
        connectHeaders.add("token", token);

        this.session = client
            .connectAsync("ws://localhost:" + port + "/ws",
                new org.springframework.web.socket.WebSocketHttpHeaders(),
                connectHeaders,
                new StompSessionHandlerAdapter() { })
            .get(10, java.util.concurrent.TimeUnit.SECONDS);

        subscribe("/topic/doc/" + documentId + "/ops", List.class, payload -> {
            for (Object entry : (List<?>) payload) {
                JsonNode node = OpCodec.mapper().valueToTree(entry).get("op");
                Op op = OpCodec.fromJson(node);
                received.add(op);
                document.apply(op);
            }
        });

        subscribe("/topic/doc/" + documentId + "/acks", List.class, payload -> {
            for (Object entry : (List<?>) payload) {
                long seq = OpCodec.mapper().valueToTree(entry).get("serverSeq").asLong();
                lastAckSeq.accumulateAndGet(seq, Math::max);
            }
        });

        session.send("/app/doc/" + documentId + "/join", Map.of());
    }

    private <T> void subscribe(String destination, Class<T> type, java.util.function.Consumer<Object> handler) {
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return type;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                if (payload != null) {
                    handler.accept(payload);
                }
            }
        });
    }

    /** Wstawia tekst lokalnie i wysyła operację — dokładnie tak, jak zrobi to edytor. */
    public void insert(int position, String text) {
        send(document.insert(position, text));
    }

    public void delete(int position, int count) {
        send(document.delete(position, count));
    }

    private void send(Op op) {
        // Wysyłamy OBIEKT, nie gotowego JSON-a w stringu. Konwerter Jacksona zserializowałby
        // string jako string JSON-owy ("{\"op\":...}"), a serwer dostałby tekst zamiast
        // struktury — i po cichu niczego nie zrobił. Kosztowało to jeden przebieg testów.
        session.send("/app/doc/" + documentId + "/op", Map.of("op", OpCodec.toJson(op)));
    }

    public void requestSync(long sinceServerSeq) {
        session.send("/app/doc/" + documentId + "/sync",
            Map.of("sinceServerSeq", sinceServerSeq, "version", Map.of()));
    }

    public String text() {
        return document.text();
    }

    public int receivedCount() {
        return received.size();
    }

    public long lastAckSeq() {
        return lastAckSeq.get();
    }

    /** Czeka, aż tekst klienta osiągnie oczekiwaną wartość, albo minie limit czasu. */
    public void awaitText(String expected, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (expected.equals(document.text())) {
                return;
            }
            sleepBriefly();
        }
        throw new AssertionError("oczekiwano \"" + expected + "\", jest \"" + document.text() + "\"");
    }

    public void awaitCondition(java.util.function.BooleanSupplier condition, Duration timeout, String what) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleepBriefly();
        }
        throw new AssertionError("nie doczekano się: " + what);
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        if (session.isConnected()) {
            session.disconnect();
        }
    }
}
