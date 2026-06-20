package dev.collabedit.app.ws;

import dev.collabedit.app.cluster.PresenceBus;
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
    private final PresenceBus bus;

    /**
     * How often this instance announces who it has, and how long another instance's announcement
     * is believed (CP26).
     *
     * <p>The ratio is the one ADR-0002 chose for STOMP heartbeats and for the same reason: one
     * lost message is normal, three in a row is an instance that is gone. There is no repair
     * mechanism here and none is needed — the next announcement carries the whole truth again,
     * because they are full lists rather than deltas.
     */
    private static final long ANNOUNCE_EVERY_MILLIS = 1_000;
    private static final long BELIEVE_FOR_MILLIS = 3_000;

    /** What other instances last said, keyed by instance and document. */
    private final Map<String, RemoteEntry> remote = new ConcurrentHashMap<>();

    /**
     * Whether the last announcement reached Redis.
     *
     * <p>Sent to the clients so the participant list can say it may be incomplete. This is the
     * one place in the system where best-effort has to be VISIBLE: everywhere else a message
     * arriving late is repaired by convergence, and a participant list that is quietly short
     * looks exactly like a document nobody else is in.
     */
    private volatile boolean clusterReachable = true;

    private record RemoteEntry(UUID documentId, List<Messages.PresenceBroadcast> participants,
                               long receivedAtMillis) {
    }

    public PresenceService(SimpMessagingTemplate messaging, DocumentRegistry registry,
                           DocumentMetrics metrics, PresenceBus bus) {
        this.messaging = messaging;
        this.registry = registry;
        this.metrics = metrics;
        this.bus = bus;
    }

    @jakarta.annotation.PostConstruct
    void subscribeToOtherInstances() {
        bus.onAnnouncement(announcement -> {
            List<Messages.PresenceBroadcast> participants = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode node : announcement.participants()) {
                participants.add(WIRE.convertValue(node, Messages.PresenceBroadcast.class));
            }
            remote.put(announcement.instanceId() + "|" + announcement.documentId(),
                new RemoteEntry(announcement.documentId(), participants,
                    System.currentTimeMillis()));
            // Only the local clients of THIS instance are told; the announcing instance already
            // told its own. Without this line a remote arrival would be invisible until the next
            // local event, which in a quiet document is never.
            broadcast(announcement.documentId());
        });
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper WIRE =
        new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Announces this instance's participants and forgets instances that have gone quiet.
     *
     * <p>Cursor movement rides this heartbeat rather than being published as it happens, and that
     * is a deliberate limit: a cursor from another instance moves at most once a second. The
     * alternative is publishing the most frequent event in the system — ~30 per second per
     * person — across the cluster, to make a decoration smoother.
     */
    @org.springframework.scheduling.annotation.Scheduled(
        fixedRate = ANNOUNCE_EVERY_MILLIS,
        // The maintenance pool, not the flow one. This publishes to Redis and walks every
        // participant, and the flow pool exists to broadcast every 10 ms with nothing queued
        // ahead of it (SchedulingConfig). Presence is soft state — the announcement being a
        // moment late costs a cursor drawn a moment late.
        scheduler = dev.collabedit.app.config.SchedulingConfig.MAINTENANCE)
    void announceAndExpire() {
        long now = System.currentTimeMillis();
        boolean reachable = true;

        for (UUID documentId : Set.copyOf(byDocument.keySet())) {
            List<Messages.PresenceBroadcast> mine = localParticipants(documentId).stream()
                .map(PresenceService::wire)
                .toList();
            reachable &= bus.publish(documentId, mine);
        }
        clusterReachable = reachable;

        // Expire, and tell the local clients about the documents that changed. A departed
        // instance's participants must disappear from the list; nothing else would remove them,
        // because the instance that could have said so is the one that is gone.
        Set<UUID> stale = new java.util.HashSet<>();
        remote.entrySet().removeIf(entry -> {
            if (now - entry.getValue().receivedAtMillis() <= BELIEVE_FOR_MILLIS) {
                return false;
            }
            stale.add(entry.getValue().documentId());
            return true;
        });
        stale.forEach(this::broadcast);
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
        Participant previous = bySession.put(sessionId, new Participant(sessionId, documentId,
            replicaId, userId, displayName, color, null, null, null, null, false));
        if (previous == null) {
            metrics.sessionOpened();
        } else if (!previous.documentId().equals(documentId)) {
            // The same session joining a DIFFERENT document. Today's client never does this — it
            // builds a fresh connection per document — but the index has to be maintained against
            // what the protocol allows, not against what one client happens to do. Without this
            // the old document keeps the session id forever: `leave` only ever cleans up the
            // document the participant is currently in.
            forgetFromDocument(previous.documentId(), sessionId);
            broadcast(previous.documentId());
        }
        byDocument.computeIfAbsent(documentId, id -> ConcurrentHashMap.newKeySet()).add(sessionId);
        broadcast(documentId);
    }

    /** Drops a session from a document's index, removing the set once it empties. */
    private void forgetFromDocument(UUID documentId, String sessionId) {
        // Removing the empty set as well, so a server that has served many documents does not
        // keep one entry per document it has ever seen — the same unbounded-growth trap the
        // permission cache and the replica registry each had to be given a bound for.
        byDocument.computeIfPresent(documentId, (id, sessions) -> {
            sessions.remove(sessionId);
            return sessions.isEmpty() ? null : sessions;
        });
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

        send(moved.documentId(), new Messages.PresenceUpdate(false, List.of(wire(moved)),
            clusterReachable));
    }

    public void leave(String sessionId) {
        Participant gone = bySession.remove(sessionId);
        if (gone == null) {
            return;
        }
        metrics.sessionClosed();
        forgetFromDocument(gone.documentId(), sessionId);
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

    /**
     * Everybody in the document, including the people connected to other instances (CP26).
     *
     * <p>Remote participants arrive as wire records rather than as {@link Participant}: this
     * instance knows nothing about their sessions and has no business inventing session
     * identifiers for them. That is why the merge happens at the broadcast, in {@link #broadcast},
     * and this method still answers the local question.
     */
    public List<Participant> participants(UUID documentId) {
        return localParticipants(documentId);
    }

    private List<Participant> localParticipants(UUID documentId) {
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
            // Two reasons to skip, and the second is why this check survives the index.
            //
            // Null: the session was removed between reading the index and reading the entry. See
            // the note on byDocument about why the two maps may disagree for an instant.
            //
            // A DIFFERENT document: the index is derived, and this is the authoritative answer.
            // The version before the index filtered every session by documentId, so "a
            // participant of X is in document X" held by construction; trusting the index alone
            // would quietly downgrade that to "holds as long as the index is maintained correctly
            // everywhere". The comparison costs one equality per participant and keeps the
            // guarantee where it was.
            if (participant != null && participant.documentId().equals(documentId)) {
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
        send(documentId, new Messages.PresenceUpdate(true, everybody(documentId),
            clusterReachable));
    }

    /**
     * Everybody in the document as the clients see them: this instance's participants plus what
     * the other instances last announced and we still believe (CP26).
     *
     * <p>Public because it is what the tests have to look at. The alternative — asserting on a
     * broadcast captured through a STOMP client — would test the messaging plumbing as well, and
     * the thing worth pinning here is the merge and the expiry.
     */
    public List<Messages.PresenceBroadcast> everybody(UUID documentId) {
        List<Messages.PresenceBroadcast> result = new ArrayList<>(
            localParticipants(documentId).stream().map(PresenceService::wire).toList());
        long now = System.currentTimeMillis();
        for (RemoteEntry entry : remote.values()) {
            if (entry.documentId().equals(documentId)
                && now - entry.receivedAtMillis() <= BELIEVE_FOR_MILLIS) {
                result.addAll(entry.participants());
            }
        }
        return result;
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
