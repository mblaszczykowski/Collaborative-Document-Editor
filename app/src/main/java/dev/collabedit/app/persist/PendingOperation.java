package dev.collabedit.app.persist;

import dev.collabedit.crdt.Op;

/** Operacja czekająca na zapis we wsadzie, wraz z autorem (audit trail — ADR-0003). */
public record PendingOperation(Op op, String userId) {
}
