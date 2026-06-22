package dev.collabedit.app.persist;

import dev.collabedit.crdt.Op;

/**
 * An operation waiting to be written as part of a batch.
 *
 * <p>{@code traceparent} is here because the write happens in a batch, on a different thread and at
 * a different moment from accepting the operation. Without carrying the context across,
 * the write span would be an orphaned trace, connected to nothing — and the most common question
 * when diagnosing slow editing is precisely "how much of this is the write".
 *
 * <p>{@code enqueuedNanos} is here for the same shape of reason, about a number rather than a
 * trace. {@code op.server.latency} is documented as "operation intake -> broadcast to this
 * instance's clients", and it was recorded immediately after the merge, on the intake thread —
 * that is, it stopped BEFORE the thing it names. The broadcast happens up to
 * {@code broadcast-window-ms} later, on the flow scheduler, so the timer and
 * {@code crdt.merge.duration} bracketed almost the same code under two different names, and the
 * one number this system can quote without clock caveats was measuring the wrong interval.
 * Carrying the intake instant with the operation is what lets the broadcast record the real one.
 *
 * @param enqueuedNanos {@code System.nanoTime} at the moment the operation was accepted. A
 * monotonic reading, not a wall clock: it is only ever subtracted from another
 * reading taken in the same process, which is the one comparison
 * {@code nanoTime} guarantees and the one {@code currentTimeMillis} does not.
 */
public record PendingOperation(Op op, String userId, String traceparent, long enqueuedNanos) {

    /**
     * Stamps the operation as arriving now.
     *
     * <p>Every production call site is at the moment of acceptance, so taking the reading here
     * removes the possibility of a caller passing a stale one. The four-argument constructor stays
     * for tests, which need to control the instant to assert on the latency at all.
     */
    public PendingOperation(Op op, String userId, String traceparent) {
        this(op, userId, traceparent, System.nanoTime());
    }
}
