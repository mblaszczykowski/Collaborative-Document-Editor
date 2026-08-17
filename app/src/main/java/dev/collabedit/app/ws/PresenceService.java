package dev.collabedit.app.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.collabedit.app.cluster.PresenceBus;
import dev.collabedit.app.config.SchedulingConfig;
import dev.collabedit.app.domain.DocumentService;
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

@Service
public class PresenceService {
    private final Map<String, Participant> bySession = new ConcurrentHashMap<>();

    private final Map<UUID, Set<String>> byDocument = new ConcurrentHashMap<>();

    private final SimpMessagingTemplate messaging;

    private final DocumentService documents;
    private final PresenceBus bus;

    private static final long ANNOUNCE_EVERY_MILLIS = 1_000;
    private static final long BELIEVE_FOR_MILLIS = 3_000;

    private final Map<UUID, Map<String, RemoteEntry>> remote = new ConcurrentHashMap<>();

    private volatile boolean clusterReachable = true;

    private record RemoteEntry(List<Messages.PresenceBroadcast> participants,
                               long receivedAtMillis) {
    }

    public PresenceService(SimpMessagingTemplate messaging, DocumentService documents,
                           PresenceBus bus) {
        this.messaging = messaging;
        this.documents = documents;
        this.bus = bus;
    }

    @PostConstruct
    void subscribeToOtherInstances() {
        bus.onAnnouncement(announcement -> {
            List<Messages.PresenceBroadcast> participants = new ArrayList<>();
            for (JsonNode node : announcement.participants()) {
                participants.add(WIRE.convertValue(node, Messages.PresenceBroadcast.class));
            }
            remote.compute(announcement.documentId(), (key, byInstance) -> {
                Map<String, RemoteEntry> entries =
                    byInstance == null ? new ConcurrentHashMap<>() : byInstance;
                entries.put(announcement.instanceId(),
                    new RemoteEntry(participants, System.currentTimeMillis()));
                return entries;
            });

            if (byDocument.containsKey(announcement.documentId())) {
                broadcast(announcement.documentId());
            }
        });
    }

    private static final ObjectMapper WIRE = new ObjectMapper();

    @Scheduled(
        fixedDelay = ANNOUNCE_EVERY_MILLIS,
        scheduler = SchedulingConfig.MAINTENANCE)
    void announceAndExpire() {
        long now = System.currentTimeMillis();
        boolean reachable = true;
        boolean published = false;

        for (UUID documentId : Set.copyOf(byDocument.keySet())) {
            List<Messages.PresenceBroadcast> mine = localParticipants(documentId).stream()
                .map(PresenceService::wire)
                .toList();
            reachable &= bus.publish(documentId, mine);
            published = true;
        }
        if (published) {
            clusterReachable = reachable;
        }

        Set<UUID> stale = new HashSet<>();
        remote.forEach((documentId, byInstance) -> {
            if (byInstance.values().removeIf(
                entry -> now - entry.receivedAtMillis > BELIEVE_FOR_MILLIS)) {
                stale.add(documentId);
            }
        });
        for (UUID documentId : Set.copyOf(remote.keySet())) {
            remote.computeIfPresent(documentId,
                (key, byInstance) -> byInstance.isEmpty() ? null : byInstance);
        }
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

    UUID recordJoin(String sessionId, UUID documentId, int replicaId,
                    String userId, String displayName, String color) {
        Participant previous = bySession.put(sessionId, new Participant(sessionId, documentId,
            replicaId, userId, displayName, color, null, null, null, null, false));
        UUID leftBehind = null;
        if (previous != null && !previous.documentId.equals(documentId)) {
            forgetFromDocument(previous.documentId(), sessionId);
            leftBehind = previous.documentId;
        }
        byDocument.compute(documentId, (id, sessions) -> {
            Set<String> members = sessions == null ? ConcurrentHashMap.newKeySet() : sessions;
            members.add(sessionId);
            return members;
        });
        return leftBehind;
    }

    void announce(UUID documentId) {
        broadcast(documentId);
    }

    private void forgetFromDocument(UUID documentId, String sessionId) {
        byDocument.computeIfPresent(documentId, (id, sessions) -> {
            sessions.remove(sessionId);
            return sessions.isEmpty() ? null : sessions;
        });
    }

    public void updateCursor(String sessionId, UUID documentId, Messages.ClientPresence update) {
        Participant[] moved = new Participant[1];
        bySession.computeIfPresent(sessionId, (id, current) -> {
            if (!current.documentId().equals(documentId)) {
                return current;
            }
            moved[0] = new Participant(
                current.sessionId(), current.documentId(), current.replicaId(),
                current.userId(), current.displayName(), current.color(),
                update.cursorReplicaId(), update.cursorLamport(),
                update.anchorReplicaId(), update.anchorLamport(),
                update.typing());
            return moved[0];
        });
        if (moved[0] == null) {
            return;
        }

        send(moved[0].documentId(), new Messages.PresenceUpdate(false, List.of(wire(moved[0])),
            clusterReachable));
    }

    Participant recordLeave(String sessionId) {
        Participant gone = bySession.remove(sessionId);
        if (gone == null) {
            return null;
        }
        forgetFromDocument(gone.documentId(), sessionId);
        return gone;
    }

    void recordDeparture(Participant gone) {
        documents.markReplicaDeparted(gone.documentId(), gone.replicaId());
    }

    private List<Participant> localParticipants(UUID documentId) {
        Set<String> sessions = byDocument.get(documentId);
        if (sessions == null) {
            return List.of();
        }
        List<Participant> result = new ArrayList<>(sessions.size());
        for (String sessionId : sessions) {
            Participant participant = bySession.get(sessionId);
            if (participant != null && participant.documentId().equals(documentId)) {
                result.add(participant);
            }
        }
        return result;
    }

    private void broadcast(UUID documentId) {
        send(documentId, new Messages.PresenceUpdate(true, everybody(documentId),
            clusterReachable));
    }

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
