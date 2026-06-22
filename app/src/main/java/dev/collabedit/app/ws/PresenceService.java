package dev.collabedit.app.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.collabedit.app.cluster.PresenceBus;
import dev.collabedit.app.config.SchedulingConfig;
import dev.collabedit.app.domain.DocumentRegistry;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Who is in a document, where their cursor is, and whether they are typing right now.
 *
 * <p>Presence is <b>ephemeral</b> state and deliberately never reaches the database: after an
 * instance restarts, clients reconnect and announce their presence themselves. Storing it would be
 * keeping information that goes stale faster than it can be read.
 *
 * <p>Cursor positions are <b>CRDT character identifiers</b>, not offsets. If Bartek's
 * cursor were "column 42", Ania inserting a character before that position would move the text but
 * not the cursor — and Bartek would be pointing at someone else's character. An identifier moves
 * with the text, because it is attached to a character rather than to a place.
 *
 * <p><b>Presence spans the cluster since CP26.</b> This map is local to the instance, and what a
 * client sees is it merged with what the other instances last announced over {@link PresenceBus} —
 * hence the {@code remote} map and {@code BELIEVE_FOR_MILLIS} below.
 *
 * <p>The merge is best-effort by construction and says so out loud: a participant list does not
 * converge, it EXPIRES. An announcement is a full list rather than a delta, so a lost one costs
 * nothing (the next carries the whole truth), and an instance that stops announcing disappears
 * after three intervals rather than lingering as a ghost. When this instance cannot vouch for the
 * whole cluster the broadcast says {@code complete: false} and the interface shows it — the one
 * place where best-effort is made visible instead of hidden.
 *
 * <p>(An earlier version of this paragraph called cluster-wide presence a deliberate simplification
 * that had been skipped. CP26 built it, in this same file, and the paragraph outlived it.)
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
     * correctness mechanism, which is exactly why a lock here would be paying for a
     * guarantee nothing needs.
     */
    private final Map<UUID, Set<String>> byDocument = new ConcurrentHashMap<>();

    private final SimpMessagingTemplate messaging;
    private final DocumentRegistry registry;
    private final PresenceBus bus;

    // DocumentMetrics used to be a dependency here, for exactly two calls: sessionOpened from
    // join and sessionClosed from leave. Neither belonged — joining a DOCUMENT is not opening a
    // SESSION, and counting one as the other made ws.sessions.opened.total double-count and
    // ws.sessions.active drift upwards. Both now happen in SessionLifecycle, where a session
    // actually begins and ends, and this class is back to being about who is in the room.

    /**
     * How often this instance announces who it has, and how long another instance's announcement
     * is believed (CP26).
     *
     * <p>The ratio is the one the transport decision chose for STOMP heartbeats and for the same reason: one
     * lost message is normal, three in a row is an instance that is gone. There is no repair
     * mechanism here and none is needed — the next announcement carries the whole truth again,
     * because they are full lists rather than deltas.
     *
     * <p>"Three" is a floor rather than an exact count, because the schedule below is
     * {@code fixedDelay}: the gap between announcements is the interval PLUS however long a run
     * takes, so under load the window covers slightly fewer than three of them. That direction is
     * the safe one — it makes this instance slower to declare another one dead, never quicker —
     * and the alternative, {@code fixedRate}, buys exactness by letting late runs pile up on a
     * thread pool this shares with every other scheduled job.
     */
    private static final long ANNOUNCE_EVERY_MILLIS = 1_000;
    private static final long BELIEVE_FOR_MILLIS = 3_000;

    /**
     * What other instances last said: documentId -> instanceId -> their participants.
     *
     * <p>Nested rather than keyed by {@code instanceId + "|" + documentId}, for the same reason
     * {@code byDocument} exists next to {@code bySession}: {@link #everybody} answers a question
     * about ONE document, and with a flat map it scanned every instance's entry for every document
     * in the cluster to do it. At 200 documents and three instances that is ~600 entries walked
     * per announcement, ~600 times a second.
     */
    private final Map<UUID, Map<String, RemoteEntry>> remote = new ConcurrentHashMap<>();

    /**
     * Whether the last announcement reached Redis.
     *
     * <p>Sent to the clients so the participant list can say it may be incomplete. This is the
     * one place in the system where best-effort has to be VISIBLE: everywhere else a message
     * arriving late is repaired by convergence, and a participant list that is quietly short
     * looks exactly like a document nobody else is in.
     */
    private volatile boolean clusterReachable = true;

    /** One instance's view of one document. The document is the key of the enclosing map. */
    private record RemoteEntry(List<Messages.PresenceBroadcast> participants,
                               long receivedAtMillis) {
    }

    public PresenceService(SimpMessagingTemplate messaging, DocumentRegistry registry,
                           PresenceBus bus) {
        this.messaging = messaging;
        this.registry = registry;
        this.bus = bus;
    }

    @PostConstruct
    void subscribeToOtherInstances() {
        bus.onAnnouncement(announcement -> {
            List<Messages.PresenceBroadcast> participants = new ArrayList<>();
            for (JsonNode node : announcement.participants()) {
                participants.add(WIRE.convertValue(node, Messages.PresenceBroadcast.class));
            }
            // compute, not computeIfAbsent-then-put. The two are not the same under the reaper in
            // announceAndExpire, which drops emptied inner maps: between taking the inner map and
            // putting into it, the reaper can remove it from `remote`, and the entry then lands in
            // a map nothing can reach. It self-heals within a second — announcements are full lists
            // — but it makes an instance's participants briefly invisible for no reason.
            remote.compute(announcement.documentId(), (key, byInstance) -> {
                Map<String, RemoteEntry> entries =
                    byInstance == null ? new ConcurrentHashMap<>() : byInstance;
                entries.put(announcement.instanceId(),
                    new RemoteEntry(participants, System.currentTimeMillis()));
                return entries;
            });

            // Only the local clients of THIS instance are told; the announcing instance already
            // told its own. Without this line a remote arrival would be invisible until the next
            // local event, which in a quiet document is never.
            //
            //...and only when there ARE local clients. convertAndSend serialises the payload
            // before the broker looks at who is subscribed, so broadcasting for a document nobody
            // here has open paid the JSON conversion and the dispatch for an empty audience — once
            // per remote announcement, that is, per document per instance per second.
            if (byDocument.containsKey(announcement.documentId())) {
                broadcast(announcement.documentId());
            }
        });
    }

    private static final ObjectMapper WIRE = new ObjectMapper();

    /**
     * Announces this instance's participants and forgets instances that have gone quiet.
     *
     * <p>Cursor movement rides this heartbeat rather than being published as it happens, and that
     * is a deliberate limit: a cursor from another instance moves at most once a second. The
     * alternative is publishing the most frequent event in the system — ~30 per second per
     * person — across the cluster, to make a decoration smoother.
     */
    @Scheduled(
        // fixedDelay, like every other scheduled job here. With fixedRate a run that
        // overran — a slow Redis publish across many documents — leaves the next one
        // already due, and they pile up on a two-thread pool.
        fixedDelay = ANNOUNCE_EVERY_MILLIS,
        // The maintenance pool, not the flow one. This publishes to Redis and walks every
        // participant, and the flow pool exists to broadcast every 10 ms with nothing queued
        // ahead of it (SchedulingConfig). Presence is soft state — the announcement being a
        // moment late costs a cursor drawn a moment late.
        scheduler = SchedulingConfig.MAINTENANCE)
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
        Set<UUID> stale = new HashSet<>();
        remote.forEach((documentId, byInstance) -> {
            if (byInstance.values().removeIf(
                entry -> now - entry.receivedAtMillis > BELIEVE_FOR_MILLIS)) {
                stale.add(documentId);
            }
        });
        // An emptied document leaves nothing behind: this map is keyed by document, so without
        // this it would grow one permanent entry per document any instance ever announced.
        //
        // Removed through compute so it cannot race the handler above: the check and the removal
        // are one atomic step on the key, so an inner map that just gained an entry is kept.
        for (UUID documentId : Set.copyOf(remote.keySet())) {
            remote.computeIfPresent(documentId,
                (key, byInstance) -> byInstance.isEmpty() ? null : byInstance);
        }
        // Same gate as the announcement handler: telling nobody costs a JSON conversion and a
        // dispatch, because convertAndSend serialises before the broker looks at subscribers.
        stale.stream().filter(byDocument::containsKey).forEach(this::broadcast);
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

    /**
     * Records a participant. <b>Map mutations only</b> — the broadcasts belong to the caller.
     *
     * <h2>Why the two halves are separate now</h2>
     * {@link SessionLifecycle} runs this inside a {@code computeIfPresent} on its own live-session
     * map, so that a join frame dispatched AFTER the session's teardown — which the multi-threaded
     * inbound channel permits — cannot re-create an entry nothing will ever remove. That only works
     * if what happens under that lock is short: dispatching messages while holding a
     * {@code ConcurrentHashMap} bin lock would trade a narrow race for a wide stall.
     *
     * <p>The session counters moved out for a different reason, stated where they now live: joining
     * a document is not the same event as opening a session, and counting one as the other
     * double-counted {@code ws.sessions.opened.total} and drifted {@code ws.sessions.active}.
     *
     * @return the document this session was previously in, when it has just moved to another one
     * and that other room needs re-announcing; {@code null} otherwise
     */
    UUID recordJoin(String sessionId, UUID documentId, int replicaId,
                    String userId, String displayName, String color) {
        Participant previous = bySession.put(sessionId, new Participant(sessionId, documentId,
            replicaId, userId, displayName, color, null, null, null, null, false));
        UUID leftBehind = null;
        if (previous != null && !previous.documentId.equals(documentId)) {
            // The same session joining a DIFFERENT document. Today's client never does this — it
            // builds a fresh connection per document — but the index has to be maintained against
            // what the protocol allows, not against what one client happens to do. Without this
            // the old document keeps the session id forever: `recordLeave` only ever cleans up the
            // document the participant is currently in.
            forgetFromDocument(previous.documentId(), sessionId);
            leftBehind = previous.documentId;
        }
        byDocument.computeIfAbsent(documentId, id -> ConcurrentHashMap.newKeySet()).add(sessionId);
        return leftBehind;
    }

    /** Tells a document's clients who is in it now. The caller decides when; see above. */
    void announce(UUID documentId) {
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

    /**
     * Records a departure. <b>Map mutations only</b> — see {@link #recordJoin}.
     *
     * @return the document to re-announce, or {@code null} when this session was in none
     */
    UUID recordLeave(String sessionId) {
        Participant gone = bySession.remove(sessionId);
        if (gone == null) {
            return null;
        }
        forgetFromDocument(gone.documentId(), sessionId);
        // The replica is recorded as DEPARTED rather than forgotten. It keeps
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
        return gone.documentId;
    }

    // A public participants used to sit here, delegating to localParticipants and promising in
    // its summary line the very thing its body did not do — "everybody in the document, including
    // the people connected to other instances". Nothing called it. everybody is the method that
    // answers that question, and localParticipants is the local half it is built from.

    private List<Participant> localParticipants(UUID documentId) {
        Set<String> sessions = byDocument.get(documentId);
        if (sessions == null) {
            return List.of();
        }
        List<Participant> result = new ArrayList<>(sessions.size());
        for (String sessionId : sessions) {
            Participant participant = bySession.get(sessionId);
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
        for (RemoteEntry entry : remote.getOrDefault(documentId, Map.of()).values()) {
            if (now - entry.receivedAtMillis <= BELIEVE_FOR_MILLIS) {
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
