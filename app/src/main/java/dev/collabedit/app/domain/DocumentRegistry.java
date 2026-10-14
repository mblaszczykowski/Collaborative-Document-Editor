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

@Component
public class DocumentRegistry {
    private static final Logger log = LoggerFactory.getLogger(DocumentRegistry.class);

    private final int serverReplicaId;

    private final Map<UUID, DocumentReplica> replicas = new ConcurrentHashMap<>();
    private final OperationStore operations;
    private final SnapshotStore snapshots;
    private final CollabEditProperties properties;

    public DocumentRegistry(OperationStore operations, SnapshotStore snapshots,
                            CollabEditProperties properties) {
        this.operations = operations;
        this.serverReplicaId = operations.allocateServerReplicaId();
        this.snapshots = snapshots;
        this.properties = properties;
    }

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

    private static final long FUTILE_RETRY_INTERVAL_MS = 1_000;

    private final AtomicLong lastFutileAttemptMillis = new AtomicLong();

    private void enforceMemoryLimit(UUID justLoaded) {
        int limit = properties.cluster().maxLoadedDocuments();
        if (replicas.size() <= limit) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastFutileAttemptMillis.get() < FUTILE_RETRY_INTERVAL_MS) {
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
            if (replica.tryEvict() && replicas.remove(replica.documentId(), replica)) {
                evicted++;
            }
        }
        if (evicted > 0) {
            lastFutileAttemptMillis.set(0);
            log.info("In-memory document limit of {} exceeded - released {} least recently used",
                limit, evicted);
        } else {
            lastFutileAttemptMillis.set(now);
            log.warn("In-memory document limit of {} exceeded ({}), and no replica could be "
                    + "released - all of them have unwritten operations",
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

        int[] replayed = {0};
        operations.forEachInRange(documentId, fromSeq, headSeq, stored -> {
            document.apply(stored.op());
            replayed[0]++;
        });
        long applied = headSeq;

        if (document.pendingCount() > 0) {
            log.error("After replay, document {} has {} operations with no cause - the log may be corrupt",
                documentId, document.pendingCount());
        }

        log.info("Loaded document {}: snapshot={}, operations from the log={}, characters={}, tombstones={}, time={}ms",
            documentId, snapshot.isPresent(), replayed[0], document.visibleLength(),
            document.tombstoneCount(), (System.nanoTime() - started) / 1_000_000);

        return new DocumentReplica(documentId, document, applied, System.currentTimeMillis());
    }

    public Collection<DocumentReplica> loaded() {
        return replicas.values();
    }

    public int loadedCount() {
        return replicas.size();
    }

    public boolean isLoaded(UUID documentId) {
        return replicas.containsKey(documentId);
    }

    public int evictIdle(long nowMillis) {
        long ttl = properties.cluster().idleDocumentEvictionMs();
        int evicted = 0;
        for (Map.Entry<UUID, DocumentReplica> entry : replicas.entrySet()) {
            DocumentReplica replica = entry.getValue();
            if (nowMillis - replica.lastActivityMillis() < ttl) {
                continue;
            }
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

    void discardCommittedReplica(UUID documentId, DocumentReplica replica) {
        replica.invalidateCommittedState();
        replicas.remove(documentId, replica);
    }

    void forget(UUID documentId) {
        replicas.remove(documentId);
    }
}
