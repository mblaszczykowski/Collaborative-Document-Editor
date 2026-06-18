package dev.collabedit.app.ws;

import dev.collabedit.app.domain.DocumentRegistry;
import dev.collabedit.app.observability.DocumentMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Who is in a document, where their cursor is, and whether they are typing right now.
 *
 * <p>Presence is <b>ephemeral</b> state and deliberately never reaches the database: after an
 * instance restarts, clients reconnect and announce their presence themselves. Storing it would be
 * keeping information that goes stale faster than it can be read.
 *
 * <p>Cursor positions are <b>CRDT character identifiers</b>, not offsets (ADR-0006). If Bartek's
 * cursor were "column 42", Ania inserting a character before that position would move the text but
 * not the cursor — and Bartek would be pointing at someone else's character. An identifier moves
 * with the text, because it is attached to a character rather than to a place.
 *
 * <p>A limitation that follows from ADR-0004: this map is <b>local to the instance</b>. A client
 * connected to a different instance will not appear in the participant list. A deliberate
 * simplification — full cluster-wide presence would need its own channel and entry expiry, and the
 * participant list is decoration, not a correctness mechanism. It goes into "what I would do
 * differently in production".
 */
@Service
public class PresenceService {

    /** sessionId -> presence. */
    private final Map<String, Participant> bySession = new ConcurrentHashMap<>();

    /**
     * documentId -> the sessions in it. A second index over the same data, maintained alongside.
     *
     * <h2>Why a second map rather than a scan</h2>
     * {@link #participants} used to walk <b>every session on the instance</b> and keep the ones
     * matching the document. That is fine with one document open and wrong with a thousand: the
     * cost of telling one document's participants about a join grew with the number of people
     * using every OTHER document, which is a coupling nothing about the feature implies.
     *
     * <p>It is the same reasoning that took {@link dev.collabedit.app.cluster.ClusterBus} from
     * parsing the whole cluster's traffic to filtering on the channel name first: at one document
     * the difference is invisible, and the shape of the cost is wrong either way.
     *
     * <p>The two maps are updated together and read separately, so they can disagree for an
     * instant under concurrency. What that costs is bounded and small — a participant list that
     * briefly includes somebody who has just left, or omits somebody who has just arrived, and
     * the next join or leave sends a full list that corrects it. Presence is a decoration, not a
     * correctness mechanism (ADR-0004), which is exactly why a lock here would be paying for a
     * guarantee nothing needs.
     */
    private final Map<UUID, Set<String>> byDocument = new ConcurrentHashMap<>();

    private final SimpMessagingTemplate messaging;
    private final DocumentRegistry registry;
    private final DocumentMetrics metrics;

    public PresenceService(SimpMessagingTemplate messaging, DocumentRegistry registry,
                           DocumentMetrics metrics) {
        this.messaging = messaging;
        this.registry = registry;
        this.metrics = metrics;
    }

    public record Participant(
        String sessionId,
        UUID documentId,
        int replicaId,
        String userId,
        String displayName,
        String color,
        Integer cursorReplicaId, Long cursorLamport,
        Integer anchorReplicaId, Long anchorLamport,
        boolean typing
    ) {
    }

    public void join(String sessionId, UUID documentId, int replicaId,
                     String userId, String displayName, String color) {
        if (bySession.put(sessionId, new Participant(sessionId, documentId, replicaId,
            userId, displayName, color, null, null, null, null, false)) == null) {
            metrics.sessionOpened();
        }
        byDocument.computeIfAbsent(documentId, id -> ConcurrentHashMap.newKeySet()).add(sessionId);
        broadcast(documentId);
    }

    /**
     * One participant's new cursor position.
     *
     * <p>Broadcast as a <b>delta</b>, not as a full list. Cursor movement is the most frequent
     * event in the system — it happens on every keystroke and on arrow-key auto-repeat (~30/s) — so
     * replying with every participant to everyone gives traffic growing with the square of the
     * number of people, almost none of which changed. The distinction is justified at
     * {@link Messages.PresenceUpdate}.
     */
    public void updateCursor(String sessionId, Messages.ClientPresence update) {
        Participant current = bySession.get(sessionId);
        if (current == null) {
            return;
        }
        Participant moved = new Participant(
            current.sessionId(), current.documentId(), current.replicaId(),
            current.userId(), current.displayName(), current.color(),
            update.cursorReplicaId(), update.cursorLamport(),
            update.anchorReplicaId(), update.anchorLamport(),
            update.typing());
        bySession.put(sessionId, moved);

        send(moved.documentId(), new Messages.PresenceUpdate(false, List.of(wire(moved))));
    }

    public void leave(String sessionId) {
        Participant gone = bySession.remove(sessionId);
        if (gone == null) {
            return;
        }
        metrics.sessionClosed();
        // Removing the empty set as well, so a server that has served many documents does not
        // keep one entry per document it has ever seen — the same unbounded-growth trap the
        // permission cache and the replica registry each had to be given a bound for.
        byDocument.computeIfPresent(gone.documentId(), (id, sessions) -> {
            sessions.remove(sessionId);
            return sessions.isEmpty() ? null : sessions;
        });
        // The replica is recorded as DEPARTED rather than forgotten (ADR-0005). It keeps
        // constraining causal stability for the GC window, because that window exists precisely
        // to let a client that closed its laptop come back and still merge.
        //
        // This line used to call forgetReplica, which removed the entry outright — so a departed
        // client constrained nothing, and the tombstones its pending work was anchored to became
        // collectable as soon as everyone still present moved past them. The comment here said
        // that was to stop one closed tab stalling compaction forever, which is a real concern
        // and is now handled by the window expiring rather than by forgetting immediately.
        if (registry.isLoaded(gone.documentId())) {
            registry.get(gone.documentId()).markDeparted(gone.replicaId(), System.currentTimeMillis());
        }
        broadcast(gone.documentId());
    }

    public List<Participant> participants(UUID documentId) {
        Set<String> sessions = byDocument.get(documentId);
        if (sessions == null) {
            return List.of();
        }
        List<Participant> result = new ArrayList<>(sessions.size());
        for (String sessionId : sessions) {
            Participant participant = bySession.get(sessionId);
            // Null when a session was removed between reading the index and reading the entry.
            // Skipped rather than guarded against: see the note on byDocument about why the two
            // maps are allowed to disagree for an instant.
            if (participant != null) {
                result.add(participant);
            }
        }
        return result;
    }

    /**
     * Broadcasts the FULL participant list — used on join and on leave.
     *
     * <p>A leave cannot be expressed as a delta: it means "this entry is gone", and a delta can
     * only say "this entry now looks like this". At this frequency a third message type would cost
     * more to maintain than sending the whole list.
     */
    private void broadcast(UUID documentId) {
        send(documentId, new Messages.PresenceUpdate(true,
            participants(documentId).stream().map(PresenceService::wire).toList()));
    }

    private void send(UUID documentId, Messages.PresenceUpdate update) {
        messaging.convertAndSend("/topic/doc/" + documentId + "/presence", update);
    }

    private static Messages.PresenceBroadcast wire(Participant p) {
        return new Messages.PresenceBroadcast(
            p.replicaId(), p.userId(), p.displayName(), p.color(),
            p.cursorReplicaId(), p.cursorLamport(),
            p.anchorReplicaId(), p.anchorLamport(),
            p.typing(), true);
    }
}
