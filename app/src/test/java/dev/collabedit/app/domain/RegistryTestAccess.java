package dev.collabedit.app.domain;

import java.util.UUID;

/**
 * The one door into {@link DocumentRegistry} that only tests may use.
 *
 * <h2>Why this class exists</h2>
 * {@code DocumentRegistry.forget} was a <b>public</b> method documented "For tests only". That is a
 * comment where a boundary was needed: nothing stops production code calling a public method, and
 * this one bypasses {@link DocumentReplica#tryEvict} — the single check that stops a replica
 * holding unwritten operations from being dropped. Dropping such a replica discards operations
 * already accepted from a client, and does it silently, which is precisely why that check was
 * written.
 *
 * <p>The method is package-private now, and this class lives in the same package under
 * {@code src/test}. That is the standard Java answer to "visible to the tests, closed to
 * {@code main}", and it makes the exception visible: a test reaching for a door has to name the
 * door.
 *
 * <h2>What the tests use it for, and why they are entitled to</h2>
 * Every caller wants the same thing — <i>force the next read to come from the database</i> — in
 * order to check the equivalence the operation-log decision rests on: that a replica rebuilt from a snapshot plus a
 * log tail is identical to the one held in memory. Without a test the snapshot would quietly become
 * the source of truth. Going through {@code tryEvict} would not serve that, because it refuses
 * exactly when a test has just written something.
 */
public final class RegistryTestAccess {

    private RegistryTestAccess() {
    }

    /** Drops a replica from memory so the next {@code get} rebuilds it from the database. */
    public static void forget(DocumentRegistry registry, UUID documentId) {
        registry.forget(documentId);
    }
}
