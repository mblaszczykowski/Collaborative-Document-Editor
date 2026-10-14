package dev.collabedit.app.domain;

import dev.collabedit.app.persist.PendingOperation;
import dev.collabedit.app.persist.StoredOperation;
import dev.collabedit.crdt.Anchor;
import dev.collabedit.crdt.Id;
import dev.collabedit.crdt.Op;
import dev.collabedit.crdt.RgaDocument;
import dev.collabedit.crdt.TreapBlockList;
import dev.collabedit.crdt.VersionVector;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Function;

public class DocumentReplica {
    private final UUID documentId;
    private final RgaDocument document;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile long appliedServerSeq;

    private final NavigableSet<Long> appliedBeyondPrefix = new TreeSet<>();

    private final List<PendingOperation> unwritten = new ArrayList<>();

    private final List<PendingOperation> pendingBroadcast = new ArrayList<>();

    private int flushesInFlight;

    private int operationsSinceSnapshot;

    private volatile long lastActivityMillis;

    private volatile long lastEditMillis;

    public DocumentReplica(UUID documentId, RgaDocument document, long appliedServerSeq, long nowMillis) {
        this.documentId = documentId;
        this.document = document;
        this.appliedServerSeq = appliedServerSeq;
        this.lastActivityMillis = nowMillis;
        this.lastEditMillis = nowMillis;
    }

    public UUID documentId() {
        return documentId;
    }

    public long appliedServerSeq() {
        return appliedServerSeq;
    }

    public long lastActivityMillis() {
        return lastActivityMillis;
    }

    public long lastEditMillis() {
        return lastEditMillis;
    }

    private boolean evicted;

    public static class StaleReplicaException extends RuntimeException {
        public StaleReplicaException(UUID documentId) {
            super("replica of document " + documentId + " was evicted while an operation "
                + "was in flight; the caller should reload it and retry");
        }
    }

    boolean tryEvict() {
        lock.lock();
        try {
            if (!unwritten.isEmpty() || !pendingBroadcast.isEmpty() || flushesInFlight > 0) {
                return false;
            }
            evicted = true;
            return true;
        } finally {
            lock.unlock();
        }
    }

    void invalidateCommittedState() {
        lock.lock();
        try {
            if (!unwritten.isEmpty() || flushesInFlight > 0) {
                throw new IllegalStateException("cannot discard uncommitted replica work");
            }
            evicted = true;
            pendingBroadcast.clear();
        } finally {
            lock.unlock();
        }
    }

    private void refuseIfEvicted() {
        if (evicted) {
            throw new StaleReplicaException(documentId);
        }
    }

    public <T> T withLock(Function<RgaDocument, T> action) {
        lock.lock();
        try {
            return action.apply(document);
        } finally {
            lock.unlock();
        }
    }

    public <T> T withActiveLock(Function<RgaDocument, T> action) {
        return withLock(document -> {
            refuseIfEvicted();
            return action.apply(document);
        });
    }

    public void checkAdmission(Op op, int cap) {
        withActiveLock(document -> {
            DocumentService.checkClockBudget(document, op);
            if (unwritten.size() >= cap && !document.hasSeen(op)) {
                throw new DocumentService.WriteBufferFullException();
            }
            if (document.pendingCount() >= cap && !document.canApplyWithoutBuffering(op)) {
                throw new RgaDocument.PendingOverflowException();
            }
            return null;
        });
    }

    public boolean merge(Op op, String userId, String traceparent, long nowMillis) {
        return merge(op, userId, traceparent, nowMillis, Integer.MAX_VALUE);
    }

    public boolean merge(Op op, String userId, String traceparent, long nowMillis, int cap) {
        lock.lock();
        try {
            refuseIfEvicted();
            if (unwritten.size() >= cap && !document.hasSeen(op)) {
                throw new DocumentService.WriteBufferFullException();
            }
            DocumentService.checkClockBudget(document, op);
            RgaDocument.ApplyResult result = document.apply(op, cap);
            lastActivityMillis = nowMillis;
            lastEditMillis = nowMillis;
            if (result.isNews()) {
                PendingOperation pending = new PendingOperation(op, userId, traceparent);
                unwritten.add(pending);
                pendingBroadcast.add(pending);
                operationsSinceSnapshot++;
            }
            return result.isNews();
        } finally {
            lock.unlock();
        }
    }

    public void enqueueForWrite(Op op, String userId, long nowMillis) {
        lock.lock();
        try {
            refuseIfEvicted();
            PendingOperation pending = new PendingOperation(op, userId, null);
            unwritten.add(pending);
            pendingBroadcast.add(pending);
            operationsSinceSnapshot++;
            lastActivityMillis = nowMillis;
            lastEditMillis = nowMillis;
        } finally {
            lock.unlock();
        }
    }

    public List<Op> applyRemote(List<StoredOperation> incoming, long nowMillis) {
        lock.lock();
        try {
            refuseIfEvicted();
            List<Op> news = new ArrayList<>();
            for (StoredOperation stored : incoming) {
                if (stored.serverSeq() <= appliedServerSeq) {
                    continue;
                }
                if (document.apply(stored.op()).isNews()) {
                    pendingBroadcast.add(new PendingOperation(stored.op(), null, null));
                    news.add(stored.op());
                }
                observeSequence(stored.serverSeq());
            }
            if (!news.isEmpty()) {
                lastActivityMillis = nowMillis;
                lastEditMillis = nowMillis;

                operationsSinceSnapshot += news.size();
            }
            return news;
        } finally {
            lock.unlock();
        }
    }

    public List<Op> generateAndEnqueue(Function<RgaDocument, List<Op>> generator, String userId,
                                       long nowMillis) {
        lock.lock();
        try {
            refuseIfEvicted();
            List<Op> generated = generator.apply(document);
            if (generated == null) {
                return null;
            }
            for (Op op : generated) {
                PendingOperation pending = new PendingOperation(op, userId, null);
                unwritten.add(pending);
                pendingBroadcast.add(pending);
                operationsSinceSnapshot++;
            }
            if (!generated.isEmpty()) {
                lastActivityMillis = nowMillis;
                lastEditMillis = nowMillis;
            }
            return generated;
        } finally {
            lock.unlock();
        }
    }

    public List<PendingOperation> drainUnwritten(int limit) {
        lock.lock();
        try {
            if (unwritten.isEmpty()) {
                return List.of();
            }
            if (unwritten.size() <= limit) {
                List<PendingOperation> batch = List.copyOf(unwritten);
                unwritten.clear();
                return batch;
            }
            List<PendingOperation> batch = List.copyOf(unwritten.subList(0, limit));
            unwritten.subList(0, limit).clear();
            return batch;
        } finally {
            lock.unlock();
        }
    }

    public void requeueUnwritten(List<PendingOperation> batch) {
        if (batch.isEmpty()) {
            return;
        }
        lock.lock();
        try {
            refuseIfEvicted();
            unwritten.addAll(0, batch);
        } finally {
            lock.unlock();
        }
    }

    public void requeueBroadcast(List<PendingOperation> batch) {
        if (batch.isEmpty()) {
            return;
        }
        lock.lock();
        try {
            if (evicted) {
                return;
            }
            pendingBroadcast.addAll(0, batch);
        } finally {
            lock.unlock();
        }
    }

    public List<PendingOperation> drainBroadcast() {
        lock.lock();
        try {
            if (pendingBroadcast.isEmpty()) {
                return List.of();
            }
            List<PendingOperation> batch = List.copyOf(pendingBroadcast);
            pendingBroadcast.clear();
            return batch;
        } finally {
            lock.unlock();
        }
    }

    public OutboundBatch drainForFlush(int writeLimit) {
        lock.lock();
        try {
            OutboundBatch outbound =
                new OutboundBatch(drainBroadcast(), drainUnwritten(writeLimit));
            if (!outbound.writes().isEmpty()) {
                flushesInFlight++;
            }
            return outbound;
        } finally {
            lock.unlock();
        }
    }

    public record OutboundBatch(List<PendingOperation> broadcast, List<PendingOperation> writes) {
    }

    public boolean hasPendingWork() {
        return withLock(document -> !unwritten.isEmpty() || !pendingBroadcast.isEmpty());
    }

    public boolean hasPendingBroadcast() {
        return withLock(document -> !pendingBroadcast.isEmpty());
    }

    public int unwrittenCount() {
        lock.lock();
        try {
            return unwritten.size();
        } finally {
            lock.unlock();
        }
    }

    public int pendingCount() {
        return withLock(RgaDocument::pendingCount);
    }

    public void flushCompleted(long highestServerSeq) {
        lock.lock();
        try {
            if (highestServerSeq > 0) {
                observeSequence(highestServerSeq);
            }
            flushesInFlight--;
        } finally {
            lock.unlock();
        }
    }

    public void flushCompleted(List<StoredOperation> written) {
        lock.lock();
        try {
            for (StoredOperation stored : written) {
                observeSequence(stored.serverSeq());
            }
            flushesInFlight--;
        } finally {
            lock.unlock();
        }
    }

    private void observeSequence(long sequence) {
        if (sequence <= appliedServerSeq) {
            return;
        }
        appliedBeyondPrefix.add(sequence);
        while (appliedBeyondPrefix.remove(appliedServerSeq + 1)) {
            appliedServerSeq++;
        }
    }

    public <T> Optional<T> withConsistentState(BiFunction<RgaDocument, Long, T> action) {
        lock.lock();
        try {
            if (evicted || flushesInFlight > 0 || !unwritten.isEmpty()
                || document.pendingCount() > 0 || !appliedBeyondPrefix.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(action.apply(document, appliedServerSeq));
        } finally {
            lock.unlock();
        }
    }

    public boolean snapshotDue(int threshold) {
        lock.lock();
        try {
            return operationsSinceSnapshot >= threshold;
        } finally {
            lock.unlock();
        }
    }

    public void snapshotTaken(int countAtCapture) {
        lock.lock();
        try {
            operationsSinceSnapshot = Math.max(0, operationsSinceSnapshot - countAtCapture);
        } finally {
            lock.unlock();
        }
    }

    public VersionVector gcWatermark() {
        return withLock(RgaDocument::gcWatermark);
    }

    public boolean isPermanentlyUnanchored(Op op) {
        return withLock(document -> {
            VersionVector collected = document.gcWatermark();
            return switch (op) {
                case Op.Insert insert -> insert.originLeft() != null
                    && isCollected(document, collected, insert.originLeft());
                case Op.Delete delete -> delete.targets().stream()
                    .anyMatch(range -> isCollected(document, collected, range.start()));
                case Op.AddMark mark ->
                    anchorCollected(document, collected, mark.start(), mark.end());
                case Op.RemoveMark mark ->
                    anchorCollected(document, collected, mark.start(), mark.end());
            };
        });
    }

    private static boolean anchorCollected(RgaDocument document, VersionVector collected,
                                           Anchor start, Anchor end) {
        return isCollected(document, collected, start.charId())
            || isCollected(document, collected, end.charId());
    }

    private static boolean isCollected(RgaDocument document, VersionVector collected, Id id) {
        return !document.contains(id) && id.lamport() <= collected.get(id.replicaId());
    }

    public int operationsSinceSnapshot() {
        lock.lock();
        try {
            return operationsSinceSnapshot;
        } finally {
            lock.unlock();
        }
    }

    public void reportProgress(long nowMillis) {
        lastActivityMillis = nowMillis;
    }

    public VersionVector stableVersion(Collection<VersionVector> live) {
        if (live.isEmpty()) {
            return VersionVector.empty();
        }
        List<VersionVector> responsive = new ArrayList<>(live);
        responsive.add(withLock(RgaDocument::version));
        return VersionVector.meet(responsive);
    }
}
