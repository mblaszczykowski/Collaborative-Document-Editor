package dev.collabedit.app.persist;

import dev.collabedit.crdt.Op;
import java.time.Instant;

/**
 * Operacja odczytana z logu — operacja CRDT plus metadane nadane przez serwer.
 *
 * <p>Rozdzielenie {@code op} od {@code serverSeq} jest istotne i celowe:
 * <ul>
 *   <li>{@code op} niesie porządek <b>przyczynowy</b> (zegar Lamporta, origin) — to nim
 *       posługuje się algorytm CRDT i tylko on decyduje o wyniku scalenia,</li>
 *   <li>{@code serverSeq} niesie porządek <b>zapisu</b> — to nim posługuje się transport
 *       przy pytaniu „czego mi brakuje" (ADR-0003, ADR-0004).</li>
 * </ul>
 * Mylenie tych dwóch porządków to klasyczne źródło błędów w systemach tego typu:
 * kusi, żeby użyć {@code serverSeq} do rozstrzygania konfliktów, co natychmiast
 * wprowadza z powrotem centralnego arbitra, którego cały ADR-0001 miał uniknąć.
 */
public record StoredOperation(
    Op op,
    long serverSeq,
    String userId,
    Instant createdAt
) {
}
