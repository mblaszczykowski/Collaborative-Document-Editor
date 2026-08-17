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

public class TestStompClient implements AutoCloseable {
    private final StompSession session;
    private final UUID documentId;
    private final RgaDocument document;

    private final Object documentLock = new Object();

    private final Object sendLock = new Object();

    private final List<Op> received = new CopyOnWriteArrayList<>();

    private final java.util.concurrent.atomic.AtomicInteger syncResponses =
        new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger accessChanges =
        new java.util.concurrent.atomic.AtomicInteger();
    private final AtomicLong lastAckSeq = new AtomicLong(0);

    private final java.util.concurrent.atomic.AtomicBoolean throttled =
        new java.util.concurrent.atomic.AtomicBoolean();

    private final java.util.concurrent.atomic.AtomicReference<String> lastErrorCode =
        new java.util.concurrent.atomic.AtomicReference<>();

    private volatile java.util.function.ObjLongConsumer<Op> onOperationReceived = (op, at) -> { };

    public TestStompClient(int port, String token, UUID documentId, int replicaId) throws Exception {
        this.documentId = documentId;
        this.document = new RgaDocument(replicaId, new TreapBlockList());

        jakarta.websocket.WebSocketContainer container =
            jakarta.websocket.ContainerProvider.getWebSocketContainer();
        container.setDefaultMaxTextMessageBufferSize(1024 * 1024);

        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient(container));
        client.setInboundMessageSizeLimit(1024 * 1024);
        client.setMessageConverter(new MappingJackson2MessageConverter());

        StompHeaders connectHeaders = new StompHeaders();
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

        subscribe("/topic/doc/" + documentId + "/access", java.util.Map.class,
            payload -> accessChanges.incrementAndGet());

        subscribe("/topic/doc/" + documentId + "/acks", List.class, payload -> {
            boolean mine = false;
            for (Object entry : (List<?>) payload) {
                var node = OpCodec.mapper().valueToTree(entry);
                long seq = node.get("serverSeq").asLong();
                lastAckSeq.accumulateAndGet(seq, Math::max);

                Id acknowledged = new Id(node.get("r").asInt(), node.get("l").asLong());
                if (unacknowledged.remove(
                    acknowledged.replicaId() + "@" + acknowledged.lamport()) != null) {
                    mine = true;
                }
                synchronized (documentLock) {
                    if (!document.contains(acknowledged)) {
                        gapDetected.set(true);
                    }
                }
            }
            if (mine) {
                throttleRounds.set(0);
            }
        });

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
            Object last = ((Map<?, ?>) payload).get("last");
            boolean finalChunk = !(last instanceof Boolean flag) || flag;
            if (finalChunk) {
                Object head = ((Map<?, ?>) payload).get("headServerSeq");
                if (head instanceof Number number) {
                    lastAckSeq.accumulateAndGet(number.longValue(), Math::max);
                }
                syncResponses.incrementAndGet();
            }
        });

        subscribe("/user/queue/errors", java.util.Map.class, payload -> {
            Map<?, ?> error = (Map<?, ?>) payload;
            lastErrorCode.set(String.valueOf(error.get("code")));
            if (!"RATE_LIMITED".equals(error.get("code"))) {
                return;
            }
            throttled.set(true);
            Object advertised = error.get("retryAfterMillis");
            long base = advertised instanceof Number number
                ? number.longValue() + THROTTLE_GRACE_MS
                : THROTTLE_FALLBACK_MS;
            int round = Math.min(throttleRounds.getAndIncrement(), 16);
            scheduleThrottledRetry(Math.min(base << round, THROTTLE_MAX_MS));
        });

        publish("/app/doc/" + documentId + "/join", Map.of());

        progressReporter = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "progress-" + replicaId);
            thread.setDaemon(true);
            return thread;
        });
        progressReporter.scheduleWithFixedDelay(this::retryThrottledOperations, 1000, 1000,
            java.util.concurrent.TimeUnit.MILLISECONDS);
        progressReporter.scheduleWithFixedDelay(this::reportProgress, 500, 500,
            java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void publish(String destination, Object payload) {
        synchronized (sendLock) {
            session.send(destination, payload);
        }
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

    public void insert(int position, String text) {
        insertReturningOp(position, text);
    }

    public void sendPresence() {
        try {
            publish("/app/doc/" + documentId + "/presence", Map.of(
                "cursorReplicaId", document.replicaId(),
                "cursorLamport", Math.max(0, document.clock() - 1),
                "typing", true));
        } catch (RuntimeException e) {
        }
    }

    public Op markReturningOp(int from, int to, String type, String value) {
        Op op;
        synchronized (documentLock) {
            op = document.addMark(from, to, type, value);
        }
        send(op);
        return op;
    }

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
        publish("/app/doc/" + documentId + "/op", Map.of("op", OpCodec.toJson(op)));
        unacknowledged.put(op.id().replicaId() + "@" + op.id().lamport(), op);
    }

    private final Map<String, Op> unacknowledged = new java.util.concurrent.ConcurrentHashMap<>();

    private static final long THROTTLE_GRACE_MS = 25;

    private static final long THROTTLE_FALLBACK_MS = 1000;

    private static final long THROTTLE_MAX_MS = 1000;

    private final java.util.concurrent.atomic.AtomicInteger throttleRounds =
        new java.util.concurrent.atomic.AtomicInteger();

    private final java.util.concurrent.atomic.AtomicBoolean retryScheduled =
        new java.util.concurrent.atomic.AtomicBoolean();

    private void scheduleThrottledRetry(long waitMillis) {
        if (progressReporter == null || !retryScheduled.compareAndSet(false, true)) {
            return;
        }
        progressReporter.schedule(() -> {
            retryScheduled.set(false);
            retryThrottledOperations();
        }, waitMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void retryThrottledOperations() {
        if (!throttled.compareAndSet(true, false)) {
            return;
        }
        for (Op pending : List.copyOf(unacknowledged.values())) {
            try {
                publish("/app/doc/" + documentId + "/op", Map.of("op", OpCodec.toJson(pending)));
            } catch (RuntimeException e) {
                throttled.set(true);
                return;
            }
        }
    }

    private java.util.concurrent.ScheduledExecutorService progressReporter;

    private final java.util.concurrent.atomic.AtomicBoolean gapDetected =
        new java.util.concurrent.atomic.AtomicBoolean();

    private void reportProgress() {
        if (!session.isConnected()) {
            return;
        }
        if (gapDetected.compareAndSet(true, false)) {
            requestSync(0);
        }
        try {
            publish("/app/doc/" + documentId + "/progress",
                Map.of("sinceServerSeq", lastAckSeq.get(), "version", Map.of(0, 0)));
        } catch (RuntimeException e) {
        }
    }

    public void requestSync(long sinceServerSeq) {
        publish("/app/doc/" + documentId + "/sync",
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

    public int accessChangeCount() {
        return accessChanges.get();
    }

    public int syncResponseCount() {
        return syncResponses.get();
    }

    public int markCount() {
        synchronized (documentLock) {
            return document.markCount();
        }
    }

    public long lastAckSeq() {
        return lastAckSeq.get();
    }

    public String lastErrorCode() {
        return lastErrorCode.get();
    }

    public void awaitCondition(java.util.function.BooleanSupplier condition, Duration timeout, String what) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleepBriefly();
        }
        throw new AssertionError("timed out waiting for: " + what);
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
