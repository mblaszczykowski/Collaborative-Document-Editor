package dev.collabedit.app.persist;

import dev.collabedit.crdt.Op;
import java.time.Instant;

/**
 * An operation read from the log — a CRDT operation plus the metadata the server assigned.
 *
 * <p>Separating {@code op} from {@code serverSeq} matters and is deliberate:
 * <ul>
 *   <li>{@code op} carries the <b>causal</b> order (the Lamport clock, the origin) — that is what
 *       the CRDT algorithm uses, and only it decides the merge result,</li>
 *   <li>{@code serverSeq} carries the <b>write</b> order — that is what the transport uses when
 *       asking "what am I missing" (ADR-0003, ADR-0004).</li>
 * </ul>
 * Conflating the two orders is a classic source of bugs in systems of this kind: it is tempting to
 * use {@code serverSeq} to resolve conflicts, which immediately reintroduces the central arbiter
 * the whole of ADR-0001 set out to avoid.
 */
public record StoredOperation(
    Op op,
    long serverSeq,
    String userId,
    Instant createdAt
) {
}
