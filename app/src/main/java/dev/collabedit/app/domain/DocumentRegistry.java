package dev.collabedit.app.domain;

import dev.collabedit.app.config.CollabEditProperties;
import dev.collabedit.app.persist.OperationStore;
import dev.collabedit.app.persist.SnapshotStore;
import dev.collabedit.app.persist.StoredOperation;
import dev.collabedit.crdt.RgaDocument;
import dev.collabedit.crdt.TreapBlockList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The document replicas held in this instance's memory — loaded on demand, released when idle.
 *
 * <p>This follows from the cluster design: a document open by clients connected to three instances lives in
 * three copies. That is the <b>deliberate cost</b> of doing without a document owner and sticky
 * sessions — written down explicitly as a known cost rather than found later. With documents in the
 * megabytes, or with a dozen or more instances, the right answer would be a change of architecture
 * rather than an optimisation here.
 */
@Component
public class DocumentRegistry {

    private static final Logger log = LoggerFactory.getLogger(DocumentRegistry.class);

    /**
     * This backend instance's replica identifier.
     *
     * <p>Negative deliberately: clients get positive identifiers (the counter in Redis), so the two
     * spaces cannot intersect even if allocation goes wrong. A server instance generates
     * operations of its own on the paths where the CLIENT must not: restoring a version from
     * history, accepting or rejecting a suggestion, and creating a recovered draft. All three go
     * through {@code DocumentService.generateAndSubmit} and are recorded as new operations, which
     * is what the operation-log decision requires. (This note used to name the restore alone, and
     * the other two arrived without it being updated.)
     */
    private final int serverReplicaId = -Math.abs(UUID.randomUUID().hashCode() % 1_000_000) - 1;

    private final Map<UUID, DocumentReplica> replicas = new ConcurrentHashMap<>();
    private final OperationStore operations;
    private final SnapshotStore snapshots;
    private final CollabEditProperties properties;

    public DocumentRegistry(OperationStore operations, SnapshotStore snapshots,
                            CollabEditProperties properties) {
        this.operations = operations;
        this.snapshots = snapshots;
        this.properties = properties;
    }

    // serverReplicaId used to sit here, a public accessor with no caller in main or in tests. The
    // field itself is load-bearing — it is what `load` builds the RgaDocument with — and its
    // javadoc above is the reason it is negative. Only the getter was decoration.

    /**
     * A document's replica, loaded from the database on first use.
     *
     * <p>Loading means a snapshot (when one is readable) plus replaying the tail of the operation
     * log. When there is no snapshot, or it is in an unsupported format, we replay the whole log —
     * slower, with an identical result. That equivalence is tested explicitly (CP5), because
     * without a test the cache would quietly become the source of truth.
     *
     * <h2>Why the load happens OUTSIDE the map</h2>
     * This was {@code replicas.computeIfAbsent(documentId, this::load)}, which runs the mapping
     * function <b>while holding the bin lock for that key</b>. And {@code load} is not a cheap
     * function: three JDBC queries plus a full replay of the log tail — it logs its own duration in
     * milliseconds, which is the giveaway that it is not a lookup. For that whole time
     * {@code ConcurrentHashMap} blocks every other operation landing in the same bin:
     * {@code evictIdle}'s {@code remove}, the cluster listener's {@code get}, an edit to an
     * <b>unrelated document</b> that happens to hash nearby.
     *
     * <p>Loading outside and finishing with {@code putIfAbsent} costs the possibility that two
     * threads load the same document at once and one copy is thrown away. That is safe rather than
     * merely tolerable: nothing has been mutated at that point — the loser is a freshly built
     * replica nobody holds a reference to, not yet in the registry, so it cannot have accepted an
     * operation — and both threads then return the same winner, which is the property that
     * actually matters.
     */
    public DocumentReplica get(UUID documentId) {
        DocumentReplica replica = replicas.get(documentId);
        if (replica == null) {
            DocumentReplica loaded = load(documentId);
            DocumentReplica winner = replicas.putIfAbsent(documentId, loaded);
            replica = winner == null ? loaded : winner;
        }
        enforceMemoryLimit(documentId);
        return replica;
    }

    /**
     * How long to wait before repeating an enforcement attempt that <b>freed nothing</b>.
     *
     * <p>{@link #enforceMemoryLimit} runs from {@link #get}, which runs on <b>every operation</b>.
     * While the limit is exceeded and nothing can be freed — every replica holding unwritten work
     * — the scan found no candidates, logged a warning and left the state unchanged, so the next
     * keystroke did all of it again: a full copy of the replica list, a {@code lastActivity} sort,
     * a lock acquisition per replica, and another WARN line. At the configured limit of 200 that
     * is 200 lock acquisitions and one log line <b>per keystroke</b>, which is to say the
     * degradation path fired hardest under exactly the load that triggered it, and buried the log
     * while doing so.
     *
     * <p><b>Only futile attempts are throttled, and the distinction is the whole design.</b> A
     * first version throttled every attempt and broke {@code limitIsEnforcedOnLoad}: opening
     * twenty-four documents in a burst left twenty-four replicas in memory, because the second
     * through twenty-fourth calls were inside the window. That is not a test being fussy — a
     * memory bound that only applies once a second is not a memory bound. An attempt that evicts
     * something clears the throttle immediately; only an attempt that finds nothing to evict is
     * worth not repeating, because nothing about the situation has changed.
     */
    private static final long FUTILE_RETRY_INTERVAL_MS = 1_000;

    /**
     * When an enforcement attempt last found nothing it could release; 0 means "never".
     *
     * <p>Zero rather than {@link Long#MIN_VALUE}, and this cost a red test to notice:
     * {@code System.currentTimeMillis() - Long.MIN_VALUE} <b>overflows</b> to a negative value,
     * so the "was the last futile attempt recent?" check was true forever and the limit was never
     * enforced at all. With zero the subtraction is just the current epoch millis, which is
     * comfortably larger than any interval.
     */
    private final AtomicLong lastFutileAttemptMillis = new AtomicLong();

    /**
     * Enforces the upper bound on how many replicas are held in memory.
     *
     * <h2>Why the idle threshold alone is not enough</h2>
     * {@link #evictIdle} releases documents untouched for five minutes, and with a single shared
     * document that was all the memory management needed. With many documents that mechanism
     * bounds nothing: a hundred people opening a hundred different documents in a minute gives a
     * hundred replicas in memory, NONE of them idle. A time limit is not a memory limit.
     *
     * <h2>What gets released</h2>
     * The least recently used replicas <b>with no unwritten operations</b>. That condition is
     * hard: releasing a replica with a non-empty buffer would discard operations already accepted
     * from a client but not yet durable. If every replica has work in flight the limit is
     * temporarily exceeded — losing data is worse than exceeding a memory bound, and the buffers
     * drain every 50 ms anyway.
     *
     * <p>The document being opened right now is excluded from the candidates; otherwise, at a
     * limit reached exactly, it could be released in the same instant it was loaded and fall into
     * a load loop.
     */
    private void enforceMemoryLimit(UUID justLoaded) {
        int limit = properties.cluster().maxLoadedDocuments();
        if (replicas.size() <= limit) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastFutileAttemptMillis.get() < FUTILE_RETRY_INTERVAL_MS) {
            // The last attempt found nothing releasable and that was moments ago. Repeating the
            // scan would take a lock on every replica to reach the same conclusion.
            return;
        }

        List<DocumentReplica> candidates = new ArrayList<>(replicas.values());
        candidates.removeIf(r -> r.documentId().equals(justLoaded));
        candidates.sort(Comparator.comparingLong(DocumentReplica::lastActivityMillis));

        int toEvict = replicas.size() - limit;
        int evicted = 0;
        for (DocumentReplica replica : candidates) {
            if (evicted >= toEvict) {
                break;
            }
            // tryEvict CLAIMS the replica under its own lock, refusing if it still holds work.
            // Asking first and removing afterwards was two steps with a window between them, and
            // an operation merged in that window landed in a replica nothing iterates — see
            // DocumentReplica.tryEvict for why that is quiet rather than loud.
            if (replica.tryEvict() && replicas.remove(replica.documentId(), replica)) {
                evicted++;
            }
        }
        if (evicted > 0) {
            lastFutileAttemptMillis.set(0);
            log.info("In-memory document limit of {} exceeded — released {} least recently used",
                limit, evicted);
        } else {
            lastFutileAttemptMillis.set(now);
            // Not swept under the carpet: the limit is exceeded and nothing could be freed. A
            // persistent state like this means the limit is too low for the number of documents
            // being edited AT ONCE, not that the mechanism is broken. Rate-limited above, so it
            // says this once a second rather than once per keystroke.
            log.warn("In-memory document limit of {} exceeded ({}), and no replica could be "
                    + "released — all of them have unwritten operations",
                limit, replicas.size());
        }
    }

    private DocumentReplica load(UUID documentId) {
        long started = System.nanoTime();
        long headSeq = operations.maxServerSeq(documentId);

        var snapshot = snapshots.findLatestUpTo(documentId, headSeq);
        RgaDocument document;
        long fromSeq;
        if (snapshot.isPresent()) {
            document = RgaDocument.restore(serverReplicaId, new TreapBlockList(),
                snapshot.get().snapshot());
            fromSeq = snapshot.get().upToServerSeq();
        } else {
            document = new RgaDocument(serverReplicaId, new TreapBlockList());
            fromSeq = 0;
        }

        List<StoredOperation> tail = operations.loadAfter(documentId, fromSeq);
        long applied = headSeq;
        for (StoredOperation stored : tail) {
            document.apply(stored.op());
            // The label has to describe what was APPLIED, not what the log held when we asked.
            //
            // headSeq is read before the tail, so an operation another instance appends in between
            // is in this list and is applied here — while appliedServerSeq claimed the older
            // number. The replica then reported itself behind operations it was already holding,
            // and anti-entropy dutifully pulled and re-applied them (harmless, idempotent) while
            // any snapshot taken in that state was labelled BELOW the state it contained — which
            // HistoryService.textAt reads as a state from the future.
            applied = Math.max(applied, stored.serverSeq());
        }

        if (document.pendingCount() > 0) {
            // An operation without its cause in the log would mean a corrupt log — something a
            // correct write cannot produce. We log loudly rather than stay silent, because
            // silently losing content is far worse than a visible alarm.
            log.error("After replay, document {} has {} operations with no cause — the log may be corrupt",
                documentId, document.pendingCount());
        }

        log.info("Loaded document {}: snapshot={}, operations from the log={}, characters={}, tombstones={}, time={}ms",
            documentId, snapshot.isPresent(), tail.size(), document.visibleLength(),
            document.tombstoneCount(), (System.nanoTime() - started) / 1_000_000);

        return new DocumentReplica(documentId, document, applied, System.currentTimeMillis());
    }

    /**
     * The replicas currently in memory.
     *
     * <p>A live view, not a copy, and the change is worth stating because {@code List.copyOf} looks
     * like the safe choice. It bought nothing here: {@code ConcurrentHashMap.values} is weakly
     * consistent, so iterating it across a concurrent {@code put} or {@code remove} is safe by
     * specification — it may or may not reflect the change, and no caller here cares which. What
     * the copy did buy was an allocation of the whole collection on a path taken about a hundred
     * times a second from the flow scheduler (the 10 ms broadcast and the 50 ms flush both walk
     * this), plus twice per Prometheus scrape.
     */
    public Collection<DocumentReplica> loaded() {
        return replicas.values();
    }

    /**
     * How many replicas are in memory — for the gauge, which needs the number and not the list.
     *
     * <p>Separate from {@link #loaded} because a gauge asking for a collection in order to call
     * {@code size} on it is how a metric ends up costing more than the thing it measures.
     */
    public int loadedCount() {
        return replicas.size();
    }

    public boolean isLoaded(UUID documentId) {
        return replicas.containsKey(documentId);
    }

    /**
     * Releases from memory the replicas of documents nobody has been interested in for a while.
     *
     * <p>A replica with an unwritten batch is not released — otherwise reclaiming memory would
     * discard operations that were accepted but are not yet durable.
     */
    public int evictIdle(long nowMillis) {
        long ttl = properties.cluster().idleDocumentEvictionMs();
        int evicted = 0;
        for (Map.Entry<UUID, DocumentReplica> entry : replicas.entrySet()) {
            DocumentReplica replica = entry.getValue();
            if (nowMillis - replica.lastActivityMillis() < ttl) {
                continue;
            }
            // Claim under the replica's own lock rather than checking and then removing: the
            // same race as in enforceMemoryLimit, and the same reason it matters.
            if (!replica.tryEvict()) {
                continue;
            }
            replicas.remove(entry.getKey(), replica);
            evicted++;
        }
        if (evicted > 0) {
            log.debug("Released {} idle documents", evicted);
        }
        return evicted;
    }

    /**
     * Drops a replica WITHOUT going through {@link DocumentReplica#tryEvict} — for tests, so they
     * can force the state to be read from the database again.
     *
     * <h2>Why this is package-private now</h2>
     * It was public and documented "For tests only", which is a comment where a boundary was
     * needed: a public method on a production component is reachable from every production caller,
     * and this one bypasses the single check that stops a replica holding unwritten operations from
     * being dropped. That check is not decoration — {@code DocumentReplica.tryEvict} exists because
     * discarding a replica with a non-empty buffer loses operations already accepted from a client.
     *
     * <p>The tests that legitimately need it reach it through {@code RegistryTestAccess}, which
     * lives in this package under {@code src/test}. That is the standard Java answer and it keeps
     * the door where it belongs: visible to the tests, closed to {@code main}.
     */
    void forget(UUID documentId) {
        replicas.remove(documentId);
    }
}
