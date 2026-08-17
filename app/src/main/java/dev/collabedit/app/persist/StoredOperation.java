package dev.collabedit.app.persist;

import dev.collabedit.crdt.Op;
import java.time.Instant;

public record StoredOperation(
    Op op,
    long serverSeq,
    String userId,
    Instant createdAt
) {
}
