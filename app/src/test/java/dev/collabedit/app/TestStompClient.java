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
 * The test client — a browser faked faithfully enough for the test to be about something.
 *
 * <p>The crucial part: the client keeps <b>its own CRDT replica</b> and applies operations received
 * from the server to it, exactly as the frontend does. Without that, a test would only check
 * whether the server broadcasts something — whereas the question is whether <b>every party arrives
 * at the same text</b>, which is the whole project's thesis.
 */
public class TestStompClient implements AutoCloseable {

    private final StompSession session;
    private final UUID documentId;
    private final RgaDocument document;

    /**
     * Access to the replica is serialised.
     *
     * <p>{@link RgaDocument} deliberately is NOT thread-safe — the algorithm's core has no business
     * dealing with concurrency (DocumentReplica handles that on the server, and the browser has one
     * thread). This test client, however, has two threads: STOMP reception and sending from the
     * load harness. Without this lock the replica's state was silently corrupted and the harness
     * reported non-convergence — that is, it measured its own bug and attributed it to the system.
     */
    private final Object documentLock = new Object();

    private final List<Op> received = new CopyOnWriteArrayList<>();
    private final AtomicLong lastAckSeq = new AtomicLong(0);

    /** Set when the server refused an operation for rate — see {@link #send}. */
    private final java.util.concurrent.atomic.AtomicBoolean throttled =
        new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Called when an operation is received, with the time of receipt.
     *
     * <p>It exists for the load harness: propagation latency can only be measured honestly when
     * sender and receiver share ONE clock. Here both ends are in the same process, so
     * {@code System.nanoTime()} on either side is comparable — which cannot be said of two browsers
     * on two machines.
     */
    private volatile java.util.function.ObjLongConsumer<Op> onOperationReceived = (op, at) -> { };

    public TestStompClient(int port, String token, UUID documentId, int replicaId) throws Exception {
        this.documentId = documentId;
        this.document = new RgaDocument(replicaId, new TreapBlockList());

        // The JSR-356 container's text buffer defaults to 8 KiB — that is, EIGHT TIMES SMALLER
        // than the frame limit this system declares in ADR-0002 (64 KiB). Without raising it, the
        // test client loses every message above 8 KiB, and it looks like operation loss on the
        // server.
        //
        // This cost one false alarm: after broadcast coalescing was introduced (CP10) the harness
        // showed non-convergence at 10 clients while the server held the complete set of
        // operations. The system would have taken the blame, except that a browser has no such
        // limit — the WebSocket API reassembles multi-frame messages itself. This is a limitation
        // of the MEASURING RIG and has to be set ABOVE the server's limit, so that the rig measures
        // the system rather than itself.
        jakarta.websocket.WebSocketContainer container =
            jakarta.websocket.ContainerProvider.getWebSocketContainer();
        container.setDefaultMaxTextMessageBufferSize(1024 * 1024);

        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient(container));
        client.setInboundMessageSizeLimit(1024 * 1024);
        client.setMessageConverter(new MappingJackson2MessageConverter());

        StompHeaders connectHeaders = new StompHeaders();
        // The token goes in a CONNECT frame header, not an HTTP header — a browser cannot set
        // headers on the WebSocket handshake (see StompConnectInterceptor).
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
            // Something got through — the rate is back to normal, so the backoff starts over.
            // Without this, one run of refusals slows the client down for the rest of the run.
            if (!((List<?>) payload).isEmpty()) {
                throttleRounds.set(0);
            }
            for (Object entry : (List<?>) payload) {
                var node = OpCodec.mapper().valueToTree(entry);
                long seq = node.get("serverSeq").asLong();
                lastAckSeq.accumulateAndGet(seq, Math::max);

                // Gap detection: an acknowledgement carries the operation's identity, so not
                // holding that operation means its broadcast never reached us.
                Id acknowledged = new Id(node.get("r").asInt(), node.get("l").asLong());
                unacknowledged.remove(acknowledged.replicaId() + "@" + acknowledged.lamport());
                synchronized (documentLock) {
                    if (!document.contains(acknowledged)) {
                        gapDetected.set(true);
                    }
                }
            }
        });

        // Responses to a resynchronisation request. Without this subscription `requestSync` sent
        // a question whose answer went nowhere — and a detected gap never healed. The real frontend
        // subscribes to this channel from the start; it was missing ONLY in the measuring rig,
        // which for a while looked like a defect in the system.
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

        // Rate-limit refusals. A real client retries after the time ADVERTISED BY THE SERVER
        // (connection.ts, CP20), so the rig has to do the same — otherwise it measures a different
        // recovery mechanism from the one running in the browser, and the p99 numbers in the README
        // come from this very harness.
        subscribe("/user/queue/errors", java.util.Map.class, payload -> {
            Map<?, ?> error = (Map<?, ?>) payload;
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

        session.send("/app/doc/" + documentId + "/join", Map.of());

        // The progress report — the same one a real client sends. From it the server detects that
        // a replica is behind and sends the missing operations. Without this the harness would
        // measure the system WITHOUT its repair mechanism, which is not the one running in the
        // browser.
        progressReporter = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "progress-" + replicaId);
            thread.setDaemon(true);
            return thread;
        });
        // A safety net, not the main path. The retry is now scheduled by the refusal message, for
        // the time the server advertised; this sweep only catches the case where that message was
        // lost.
        progressReporter.scheduleWithFixedDelay(this::retryThrottledOperations, 1000, 1000,
            java.util.concurrent.TimeUnit.MILLISECONDS);
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

    /** Inserts text locally and sends the operation — exactly as the editor does. */
    public void insert(int position, String text) {
        insertReturningOp(position, text);
    }

    /** Like {@link #insert}, but returns the operation — the load harness timestamps it. */
    /**
     * Creates a mark locally and sends it (CP13/CP14).
     *
     * <p>Separate from {@link #insert}, because the authorization test has to be able to send
     * EXACTLY a mark operation and check that it gets through where a text insertion does not.
     */
    /**
     * Sends a presence report — the same one a real client sends on cursor movement.
     *
     * <p>It exists for the harness: up to CP15 the rig generated NO presence traffic, so it
     * measured a lighter system than the one a browser uses.
     */
    public void sendPresence() {
        try {
            session.send("/app/doc/" + documentId + "/presence", Map.of(
                "cursorReplicaId", document.replicaId(),
                "cursorLamport", Math.max(0, document.clock() - 1),
                "typing", true));
        } catch (RuntimeException e) {
            // Presence is best-effort — its failure must not bring down a measurement whose
            // subject is operation propagation.
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
        // We send an OBJECT, not ready-made JSON in a string. The Jackson converter would
        // serialise a string as a JSON string ("{\"op\":...}"), and the server would receive text
        // instead of a structure — and silently do nothing. This cost one test run.
        session.send("/app/doc/" + documentId + "/op", Map.of("op", OpCodec.toJson(op)));
        unacknowledged.put(op.id().replicaId() + "@" + op.id().lamport(), op);
    }

    /** The buffer of unacknowledged operations — the counterpart of `unacknowledged` in connection.ts. */
    private final Map<String, Op> unacknowledged = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Retries unacknowledged operations after a rate-limit refusal.
     *
     * <p><b>On its own schedule, not on the back of the next send</b> — and that matters. The first
     * version checked the flag inside {@code send}, so when the client's LAST operations were
     * refused, nobody retried them: the loop ended and the signal was lost. A browser does not have
     * this problem because it uses a timer (connection.ts), so the rig has to do the same —
     * otherwise it measures a weaker recovery mechanism than the one actually running for the user.
     */
    /**
     * Headroom on top of the time the server advertised — the same as in {@code connection.ts}.
     *
     * <p>The bucket refills continuously and the two sides' clocks are not synchronised, so a retry
     * a millisecond too early is refused a second time.
     */
    private static final long THROTTLE_GRACE_MS = 25;

    /** When the server advertised no time — the pre-CP20 behaviour. */
    private static final long THROTTLE_FALLBACK_MS = 1000;

    /** The upper bound on the backoff under consecutive refusals — the pre-CP20 constant's value. */
    private static final long THROTTLE_MAX_MS = 1000;

    /**
     * Consecutive refusals, for exponential backoff.
     *
     * <p>The same mechanism as in {@code connection.ts} and for the same measured reason: the time
     * the server advertises is for ONE token, while the client has dozens of operations buffered —
     * so retrying after that time turns into polling in a loop. The harness showed p99 rising from
     * 872 ms to 1352 ms from adding the server's time alone, without backoff.
     */
    private final java.util.concurrent.atomic.AtomicInteger throttleRounds =
        new java.util.concurrent.atomic.AtomicInteger();

    private final java.util.concurrent.atomic.AtomicBoolean retryScheduled =
        new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Schedules ONE retry of the whole buffer after the given wait.
     *
     * <p>One per run of refusals, not one per refused frame: when the limit is exceeded, many
     * frames are usually refused at once, and a separate retry for each would recreate exactly the
     * burst the limit just refused.
     */
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
                session.send("/app/doc/" + documentId + "/op", Map.of("op", OpCodec.toJson(pending)));
            } catch (RuntimeException e) {
                // The retry is best-effort; the next refusal will set the flag again.
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
        // A gap detected from the acknowledgements -> ask for the missing operations. We rewind
        // the cursor to the last CONFIRMED WRITE, because everything below it we know for certain.
        if (gapDetected.compareAndSet(true, false)) {
            requestSync(0);
        }
        try {
            session.send("/app/doc/" + documentId + "/progress",
                Map.of("sinceServerSeq", lastAckSeq.get(), "version", Map.of(0, 0)));
        } catch (RuntimeException e) {
            // The progress report is best-effort — its failure must not bring down the client or
            // the test.
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

    /** How many mark operations this replica knows — for checking whether formatting arrived. */
    public int markCount() {
        synchronized (documentLock) {
            return document.markCount();
        }
    }

    public long lastAckSeq() {
        return lastAckSeq.get();
    }

    /** Waits until the client's text reaches the expected value, or the timeout elapses. */
    public void awaitText(String expected, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (expected.equals(document.text())) {
                return;
            }
            sleepBriefly();
        }
        throw new AssertionError("expected \"" + expected + "\", got \"" + document.text() + "\"");
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
