package dev.collabedit.app.persist;

import dev.collabedit.crdt.Op;

public record PendingOperation(Op op, String userId, String traceparent, long enqueuedNanos) {
    public PendingOperation(Op op, String userId, String traceparent) {
        this(op, userId, traceparent, System.nanoTime());
    }
}
