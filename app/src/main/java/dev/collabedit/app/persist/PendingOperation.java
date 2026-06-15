package dev.collabedit.app.persist;

import dev.collabedit.crdt.Op;

/**
 * An operation waiting to be written as part of a batch.
 *
 * <p>{@code traceparent} is here because the write happens in a batch, on a different thread and at
 * a different moment from accepting the operation (ADR-0003). Without carrying the context across,
 * the write span would be an orphaned trace, connected to nothing — and the most common question
 * when diagnosing slow editing is precisely "how much of this is the write".
 */
public record PendingOperation(Op op, String userId, String traceparent) {
}
