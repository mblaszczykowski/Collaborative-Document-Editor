package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.collabedit.app.auth.TokenService;
import dev.collabedit.app.domain.DocumentRegistry;
import dev.collabedit.app.domain.DocumentService;
import dev.collabedit.app.persist.DocumentCatalog;
import dev.collabedit.app.persist.OperationStore;
import dev.collabedit.app.persist.PendingOperation;
import dev.collabedit.app.persist.ReplicaProgressStore;
import dev.collabedit.app.suggestions.SuggestionService;
import dev.collabedit.crdt.Id;
import dev.collabedit.crdt.RgaDocument;
import dev.collabedit.crdt.TreapBlockList;
import dev.collabedit.crdt.VersionVector;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

class DurabilityRegressionIT extends IntegrationTestBase {
    @LocalServerPort int port;
    @Autowired TokenService tokens;
    @Autowired DocumentService documents;
    @Autowired DocumentRegistry replicas;
    @Autowired OperationStore operations;
    @Autowired DocumentCatalog catalog;
    @Autowired ReplicaProgressStore progress;
    @Autowired SuggestionService suggestions;
    @Autowired JdbcTemplate jdbc;

    private UUID document() {
        UUID id = UUID.randomUUID();
        catalog.create(id, "Durability regression", null);
        return id;
    }

    @Test
    void localFlushRepairsMissedGossipBeforeAdvancingSnapshotBoundary() {
        UUID id = document();
        var replica = replicas.get(id);
        replica.withLock(model -> {
            RgaDocument remote = new RgaDocument(91);
            operations.append(id, List.of(new PendingOperation(remote.insert(0, "A"), null, null)));
            RgaDocument local = new RgaDocument(92);
            documents.submit(id, local.insert(0, "B"), null);
            documents.flushAll();
            assertThat(replica.appliedServerSeq()).isEqualTo(2);
            assertThat(model.text()).contains("A", "B");
            assertThat(replica.withConsistentState((live, through) -> through)).contains(2L);
            return null;
        });
        var state = documents.durableState(id);
        assertThat(state.throughServerSeq()).isEqualTo(2);
        assertThat(RgaDocument.restore(0, new TreapBlockList(), state.snapshot()).text())
            .isEqualTo(replica.withLock(RgaDocument::text));
    }

    @Test
    void snapshotExcludesUncommittedEditsAndUsesItsOwnBoundary() {
        UUID id = document();
        var replica = replicas.get(id);
        replica.withLock(model -> {
            RgaDocument browser = new RgaDocument(93);
            documents.submit(id, browser.insert(0, "uncommitted"), null);
            var state = documents.durableState(id);
            assertThat(state.throughServerSeq()).isZero();
            assertThat(RgaDocument.restore(0, new TreapBlockList(), state.snapshot()).text()).isEmpty();
            return null;
        });
    }

    @Test
    void generatedCommandIsDurableImmediatelyAndRollsBackItsMetadataFailure() {
        UUID id = document();
        var replica = replicas.get(id);
        assertThatThrownBy(() -> documents.generateAndSubmit(id,
            staged -> List.of(staged.insert(0, "must roll back")), null,
            () -> {
                jdbc.update("UPDATE documents SET title = 'metadata must roll back' WHERE id = ?", id);
                throw new IllegalStateException("metadata failure");
            }))
            .isInstanceOf(IllegalStateException.class).hasMessage("metadata failure");
        assertThat(operations.maxServerSeq(id)).isZero();
        assertThat(catalog.find(id).orElseThrow().title()).isEqualTo("Durability regression");
        assertThat(replica.withLock(RgaDocument::text)).isEmpty();

        documents.generateAndSubmit(id, staged -> List.of(staged.insert(0, "committed")), null);
        assertThat(operations.maxServerSeq(id)).isEqualTo(1);
        assertThat(replica.unwrittenCount()).isZero();
        var state = documents.durableState(id);
        assertThat(RgaDocument.restore(0, new TreapBlockList(), state.snapshot()).text())
            .isEqualTo("committed");
    }

    @Test
    void invalidSuggestionDoesNotCreateAnOrphanAndValidSuggestionIsImmediatelyDurable() {
        UUID id = document();
        String user = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO users(id, display_name, color) VALUES (?, 'reviewer', '#123456')", user);
        assertThat(suggestions.proposeInsertion(id, user, new Id(94, 123), "lost")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM suggestions WHERE doc_id = ?",
            Integer.class, id)).isZero();
        var proposed = suggestions.proposeInsertion(id, user, null, "draft");
        assertThat(proposed).isPresent();
        assertThat(operations.maxServerSeq(id)).isEqualTo(2);
        assertThat(replicas.get(id).unwrittenCount()).isZero();
        assertThat(suggestions.resolve(id, proposed.orElseThrow(), user, true))
            .isEqualTo(SuggestionService.Outcome.RESOLVED);
        assertThat(operations.maxServerSeq(id)).isEqualTo(3);
        assertThat(suggestions.openFor(id)).isEmpty();
    }

    @Test
    void unrecordedDepartureReceivesTheSameOfflineGraceAsCleanDisconnect() {
        UUID id = document();
        VersionVector reported = VersionVector.of(Map.of(95, 5L));
        progress.record(id, 95, reported);
        jdbc.update("UPDATE replica_progress SET reported_at = now() - interval '2 minutes' "
            + "WHERE doc_id = ?", id);
        assertThat(progress.liveFor(id, 60_000, 7L * 24 * 60 * 60 * 1000)).contains(reported);
    }

    @Test
    void durableSnapshotStopsBeforeAnUnresolvedCommittedTail() {
        UUID id = document();
        var waiting = new dev.collabedit.crdt.Op.Insert(new Id(96, 1), new Id(96, 0), "B");
        operations.append(id, List.of(new PendingOperation(waiting, null, null)));
        var state = documents.durableState(id);
        assertThat(state.throughServerSeq()).isZero();
        assertThat(RgaDocument.restore(0, new TreapBlockList(), state.snapshot()).text()).isEmpty();
        operations.append(id, List.of(new PendingOperation(
            new dev.collabedit.crdt.Op.Insert(new Id(96, 0), null, "A"), null, null)));
        state = documents.durableState(id);
        assertThat(state.throughServerSeq()).isEqualTo(2);
        assertThat(RgaDocument.restore(0, new TreapBlockList(), state.snapshot()).text())
            .isEqualTo("AB");
    }

    @Test
    void renameRefreshesContentSearchAndRejectsStalePreviewOverwrite() {
        UUID id = document();
        String user = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO users(id, display_name, color) VALUES (?, 'reviewer', '#123456')", user);
        jdbc.update("UPDATE documents SET owner_id = ? WHERE id = ?", user, id);
        documents.generateAndSubmit(id,
            staged -> List.of(staged.insert(0, "bodyword")), user);
        assertThat(catalog.rename(id, "oldtitleword", "bodyword")).isTrue();
        assertThat(catalog.search(user, null, "oldtitleword", 10)).hasSize(1);
        assertThat(catalog.rename(id, "newtitleword", "bodyword")).isTrue();
        assertThat(catalog.search(user, null, "oldtitleword", 10)).isEmpty();
        assertThat(catalog.search(user, null, "newtitleword", 10)).hasSize(1);
        assertThat(catalog.search(user, null, "bodyword", 10)).hasSize(1);
        assertThat(catalog.updatePreview(id, "stale", 0)).isFalse();
        assertThat(catalog.find(id).orElseThrow().preview()).isEqualTo("bodyword");
    }

    @Test
    void recoveredHttpResponseContainsDurablyCommittedTextAndFormatting() throws Exception {
        String user = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO users(id, display_name, color) VALUES (?, 'reviewer', '#123456')", user);
        String token = tokens.issue(new TokenService.Principal(user, "reviewer", "#123456"));
        HttpRequest request = HttpRequest.newBuilder(
            URI.create("http://localhost:" + port + "/api/documents/recovered"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""
                {"title":"Recovered formatted text","text":"abc","marks":[
                  {"from":0,"to":3,"marks":{"bold":null,"comment":"missing-thread"}}
                ]}
                """)).build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        UUID id = UUID.fromString(dev.collabedit.crdt.OpCodec.mapper().readTree(response.body())
            .path("id").asText());
        assertThat(operations.maxServerSeq(id)).isEqualTo(2);
        var state = documents.durableState(id);
        var restored = RgaDocument.restore(0, new TreapBlockList(), state.snapshot());
        assertThat(restored.text()).isEqualTo("abc");
        assertThat(restored.formatting()).singleElement().satisfies(span -> {
            assertThat(span.marks()).containsKey("bold").doesNotContainKey("comment");
        });
        assertThat(replicas.get(id).unwrittenCount()).isZero();
    }

    @Test
    void creatingDocumentInAnotherUsersWorkspaceIsForbidden() throws Exception {
        String owner = UUID.randomUUID().toString();
        String other = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO users(id, display_name, color) VALUES (?, 'owner', '#123456'), "
            + "(?, 'other', '#654321')", owner, other);
        UUID workspace = UUID.randomUUID();
        catalog.createWorkspace(workspace, "Private workspace", owner);
        String token = tokens.issue(new TokenService.Principal(other, "other", "#654321"));
        HttpRequest request = HttpRequest.newBuilder(
            URI.create("http://localhost:" + port + "/api/documents"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"Intrusion\",\"workspaceId\":\""
                + workspace + "\"}")).build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM documents WHERE workspace_id = ?",
            Integer.class, workspace)).isZero();
    }

    @Test
    void conflictingConcurrentClaimsCannotMutateTwoReplicasDifferently() throws Exception {
        UUID id = document();
        var first = new dev.collabedit.app.domain.DocumentReplica(id, new RgaDocument(-101), 0, 0);
        var second = new dev.collabedit.app.domain.DocumentReplica(id, new RgaDocument(-102), 0, 0);
        var opA = new dev.collabedit.crdt.Op.Insert(new Id(97, 0), null, "A");
        var opB = new dev.collabedit.crdt.Op.Insert(new Id(97, 0), null, "B");
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> acceptA = () -> {
                barrier.await();
                try {
                    operations.claim(id, opA);
                    first.merge(opA, null, null, 0);
                    return true;
                } catch (DocumentService.ReplicaIdConflictException conflict) { return false; }
            };
            java.util.concurrent.Callable<Boolean> acceptB = () -> {
                barrier.await();
                try {
                    operations.claim(id, opB);
                    second.merge(opB, null, null, 0);
                    return true;
                } catch (DocumentService.ReplicaIdConflictException conflict) { return false; }
            };
            var a = pool.submit(acceptA);
            var b = pool.submit(acceptB);
            boolean acceptedA = a.get();
            boolean acceptedB = b.get();
            assertThat(acceptedA ^ acceptedB).isTrue();
            var winner = acceptedA ? first : second;
            var loser = acceptedA ? second : first;
            assertThat(loser.withLock(RgaDocument::text)).isEmpty();
            operations.append(id, winner.drainUnwritten(100));
            assertThat(operations.claim(id, acceptedA ? opA : opB)).isPresent();
            assertThatThrownBy(() -> operations.claim(id, acceptedA ? opB : opA))
                .isInstanceOf(DocumentService.ReplicaIdConflictException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_identity_claims WHERE doc_id = ?",
                Integer.class, id)).isZero();
        }
    }

    @Test
    void staleRenameCannotOverwriteANewerIndexedBody() {
        UUID id = document();
        documents.generateAndSubmit(id, staged -> List.of(staged.insert(0, "N")), null);
        var old = documents.durableState(id);
        documents.generateAndSubmit(id, staged -> List.of(staged.insert(1, "plus")), null);
        documents.refreshSearchIndexes();
        assertThat(catalog.rename(id, "New title", "N", old.throughServerSeq())).isTrue();
        documents.invalidateSearchIndex(id);
        assertThat(catalog.find(id).orElseThrow().preview()).isEqualTo("Nplus");
        documents.refreshSearchIndexes();
        assertThat(catalog.find(id).orElseThrow().preview()).isEqualTo("Nplus");
    }

    @Test
    void ceilingClockEditIsRejectedBeforeClaimAndCannotPoisonFollowingEdits() {
        UUID id = document();
        var replica = replicas.get(id);
        var hostile = new dev.collabedit.crdt.Op.Insert(
            new Id(98, dev.collabedit.crdt.OpCodec.MAX_LAMPORT), null, "poison");
        assertThatThrownBy(() -> documents.submit(id, hostile, null))
            .isInstanceOf(DocumentService.ResyncRequiredException.class)
            .hasMessageContaining("recover or export");
        assertThat(replica.withLock(RgaDocument::clock)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_identity_claims WHERE doc_id = ?",
            Integer.class, id)).isZero();
        documents.generateAndSubmit(id, staged -> List.of(staged.insert(0, "healthy")), null);
        assertThat(documents.textOf(id)).isEqualTo("healthy");
        assertThat(operations.maxServerSeq(id)).isEqualTo(1);
    }
}
