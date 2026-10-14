package dev.collabedit.app.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.collabedit.app.cluster.ClusterBus;
import dev.collabedit.app.config.CollabEditProperties;
import dev.collabedit.app.observability.DocumentMetrics;
import dev.collabedit.app.observability.EditTracer;
import dev.collabedit.app.persist.DocumentCatalog;
import dev.collabedit.app.persist.OperationStore;
import dev.collabedit.app.persist.PendingOperation;
import dev.collabedit.app.persist.ReplicaProgressStore;
import dev.collabedit.app.persist.SnapshotStore;
import dev.collabedit.app.persist.StoredOperation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GeneratedCommandCommitTest {
    @Test
    void postCommitReplayFailureDoesNotReportCommandFailureOrRepeatItsGenerator() {
        UUID id = UUID.randomUUID();
        var operations = mock(OperationStore.class);
        var snapshots = mock(SnapshotStore.class);
        var properties = new CollabEditProperties(null, null, null, null, null);
        when(operations.allocateServerReplicaId()).thenReturn(-1);
        when(snapshots.findLatestUpTo(eq(id), anyLong())).thenReturn(Optional.empty());
        when(operations.loadAfter(eq(id), eq(0L), anyInt())).thenReturn(List.of());
        when(operations.loadAfter(eq(id), eq(1L), anyInt()))
            .thenThrow(new IllegalStateException("database read failed after commit"));
        when(operations.commitGenerated(eq(id), anyList(), any(Runnable.class))).thenAnswer(call -> {
            List<PendingOperation> pending = call.getArgument(1);
            return List.of(new StoredOperation(pending.getFirst().op(), 1, null, Instant.EPOCH));
        });
        var registry = new DocumentRegistry(operations, snapshots, properties);
        var service = new DocumentService(registry, operations, snapshots,
            mock(ReplicaProgressStore.class), mock(DocumentCatalog.class), mock(ClusterBus.class),
            properties, mock(DocumentMetrics.class), mock(EditTracer.class));
        int[] generated = {0};
        var result = service.generateAndSubmit(id, staged -> {
            generated[0]++;
            return List.of(staged.insert(0, "committed"));
        }, null);
        assertThat(result).hasSize(1);
        assertThat(generated[0]).isEqualTo(1);
        assertThat(registry.isLoaded(id)).isFalse();
        verify(operations, times(1)).commitGenerated(eq(id), anyList(), any(Runnable.class));
    }

    @Test
    void generatedDeletesRemainWithinTheTransportBudgetForFragmentedDocuments() {
        var model = new dev.collabedit.crdt.RgaDocument(-1, new dev.collabedit.crdt.TreapBlockList());
        for (int i = 0; i < 3_000; i++) {
            model.apply(new dev.collabedit.crdt.Op.Insert(new dev.collabedit.crdt.Id(i + 10, 0),
                null, "x"));
        }
        var deletes = DocumentService.deleteInChunks(model, 0, model.visibleLength());
        assertThat(deletes).hasSize(3);
        assertThat(model.text()).isEmpty();
        assertThat(deletes).allSatisfy(op -> {
            assertThat(((dev.collabedit.crdt.Op.Delete) op).targets()).hasSizeLessThanOrEqualTo(1_024);
            assertThat(dev.collabedit.crdt.OpCodec.encode(op)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThan(64_000);
        });
    }
}
