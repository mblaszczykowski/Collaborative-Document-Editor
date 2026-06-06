package dev.collabedit.app;

import com.fasterxml.jackson.databind.JsonNode;
import dev.collabedit.crdt.Id;
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

    /**
     * Dostęp do repliki jest serializowany.
     *
     * <p>{@link RgaDocument} celowo NIE jest bezpieczny wątkowo — rdzeń algorytmu nie ma się
     * zajmować współbieżnością (tym zajmuje się DocumentReplica po stronie serwera, a po
     * stronie przeglądarki jest jeden wątek). W tym kliencie testowym są jednak dwa wątki:
     * odbiór ze STOMP i wysyłka z harnessu obciążeniowego. Bez tej blokady stan repliki
     * ulegał cichemu uszkodzeniu, a harness raportował brak zbieżności — czyli mierzył
     * własny błąd i przypisywał go systemowi.
     */
    private final Object documentLock = new Object();

    private final List<Op> received = new CopyOnWriteArrayList<>();
    private final AtomicLong lastAckSeq = new AtomicLong(0);

    /**
     * Wywoływane przy odebraniu operacji, z czasem odbioru.
     *
     * <p>Istnieje dla harnessu obciążeniowego: latencję propagacji da się zmierzyć uczciwie
     * tylko wtedy, gdy nadawca i odbiorca dzielą JEDEN zegar. Tutaj oba końce są w tym samym
     * procesie, więc {@code System.nanoTime()} po obu stronach jest porównywalny — czego
     * nie da się powiedzieć o dwóch przeglądarkach na dwóch maszynach.
     */
    private volatile java.util.function.ObjLongConsumer<Op> onOperationReceived = (op, at) -> { };

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
                synchronized (documentLock) {
                    document.apply(op);
                }
                onOperationReceived.accept(op, System.nanoTime());
            }
        });

        subscribe("/topic/doc/" + documentId + "/acks", List.class, payload -> {
            for (Object entry : (List<?>) payload) {
                var node = OpCodec.mapper().valueToTree(entry);
                long seq = node.get("serverSeq").asLong();
                lastAckSeq.accumulateAndGet(seq, Math::max);

                // Wykrywanie luk: potwierdzenie niesie tożsamość operacji, więc brak tej
                // operacji u nas oznacza, że rozgłoszenie do nas nie dotarło.
                Id acknowledged = new Id(node.get("r").asInt(), node.get("l").asLong());
                synchronized (documentLock) {
                    if (!document.contains(acknowledged)) {
                        gapDetected.set(true);
                    }
                }
            }
        });

        // Odpowiedzi na prośbę o resynchronizację. Bez tej subskrypcji `requestSync`
        // wysyłał pytanie, na które odpowiedź szła w próżnię — a wykryta luka nigdy się
        // nie zabliźniała. Prawdziwy frontend subskrybuje ten kanał od początku;
        // brakowało go WYŁĄCZNIE w stanowisku pomiarowym, co przez chwilę wyglądało
        // jak wada systemu.
        subscribe("/user/queue/sync", java.util.Map.class, payload -> {
            Object ops = ((Map<?, ?>) payload).get("ops");
            if (ops instanceof List<?> list) {
                for (Object entry : list) {
                    Op op = OpCodec.fromJson(OpCodec.mapper().valueToTree(entry));
                    synchronized (documentLock) {
                        document.apply(op);
                    }
                }
            }
            Object head = ((Map<?, ?>) payload).get("headServerSeq");
            if (head instanceof Number number) {
                lastAckSeq.accumulateAndGet(number.longValue(), Math::max);
            }
        });

        session.send("/app/doc/" + documentId + "/join", Map.of());

        // Raport postępu — ten sam, który wysyła prawdziwy klient. Serwer wykrywa po nim,
        // że replika jest w tyle, i dosyła brakujące operacje. Bez tego harness mierzyłby
        // system BEZ mechanizmu naprawczego, czyli nie ten, który działa w przeglądarce.
        progressReporter = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "progress-" + replicaId);
            thread.setDaemon(true);
            return thread;
        });
        progressReporter.scheduleWithFixedDelay(this::reportProgress, 500, 500,
            java.util.concurrent.TimeUnit.MILLISECONDS);
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
        insertReturningOp(position, text);
    }

    /** Jak {@link #insert}, ale zwraca operację — harness obciążeniowy stempluje ją czasem. */
    public Op insertReturningOp(int position, String text) {
        Op op;
        synchronized (documentLock) {
            op = document.insert(position, text);
        }
        send(op);
        return op;
    }

    public void delete(int position, int count) {
        Op op;
        synchronized (documentLock) {
            op = document.delete(position, count);
        }
        send(op);
    }

    private void send(Op op) {
        // Wysyłamy OBIEKT, nie gotowego JSON-a w stringu. Konwerter Jacksona zserializowałby
        // string jako string JSON-owy ("{\"op\":...}"), a serwer dostałby tekst zamiast
        // struktury — i po cichu niczego nie zrobił. Kosztowało to jeden przebieg testów.
        session.send("/app/doc/" + documentId + "/op", Map.of("op", OpCodec.toJson(op)));
    }

    private java.util.concurrent.ScheduledExecutorService progressReporter;

    private final java.util.concurrent.atomic.AtomicBoolean gapDetected =
        new java.util.concurrent.atomic.AtomicBoolean();

    private void reportProgress() {
        if (!session.isConnected()) {
            return;
        }
        // Luka wykryta z potwierdzeń → prosimy o brakujące operacje. Kursor cofamy do
        // ostatniego POTWIERDZONEGO ZAPISU, bo o tym, co poniżej, wiemy na pewno.
        if (gapDetected.compareAndSet(true, false)) {
            requestSync(0);
        }
        try {
            session.send("/app/doc/" + documentId + "/progress",
                Map.of("sinceServerSeq", lastAckSeq.get(), "version", Map.of(0, 0)));
        } catch (RuntimeException e) {
            // Raport postępu jest najlepszym wysiłkiem — jego niepowodzenie nie może
            // przewrócić klienta ani testu.
        }
    }

    public void requestSync(long sinceServerSeq) {
        session.send("/app/doc/" + documentId + "/sync",
            Map.of("sinceServerSeq", sinceServerSeq, "version", Map.of()));
    }

    public void onOperationReceived(java.util.function.ObjLongConsumer<Op> listener) {
        this.onOperationReceived = listener;
    }

    public String text() {
        synchronized (documentLock) {
            return document.text();
        }
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
        if (progressReporter != null) {
            progressReporter.shutdownNow();
        }
        if (session.isConnected()) {
            session.disconnect();
        }
    }
}
