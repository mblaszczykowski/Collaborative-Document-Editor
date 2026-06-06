package dev.collabedit.app.persist;

import dev.collabedit.crdt.Op;

/**
 * Operacja czekająca na zapis we wsadzie.
 *
 * <p>{@code traceparent} jest tu, bo zapis dzieje się wsadowo, na innym wątku i w innej
 * chwili niż przyjęcie operacji (ADR-0003). Bez przeniesienia kontekstu span zapisu byłby
 * osieroconym trace'em, niepowiązanym z niczym — a najczęstsze pytanie przy diagnozowaniu
 * wolnej edycji brzmi właśnie „ile z tego to zapis".
 */
public record PendingOperation(Op op, String userId, String traceparent) {
}
