package dev.collabedit.app.ws;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

public final class Messages {
    private Messages() {
    }

    public record ClientOp(JsonNode op) {
    }

    public record SyncRequest(long sinceServerSeq, Map<Integer, Long> version) {
    }

    public record ClientPresence(
        Integer cursorReplicaId, Long cursorLamport,
        Integer anchorReplicaId, Long anchorLamport,
        boolean typing
    ) {
    }

    public record OpBroadcast(JsonNode op) {
    }

    public record OpAck(int r, long l, long serverSeq) {
    }

    public record SyncResponse(List<JsonNode> ops, long headServerSeq, boolean last) {
    }

    public record SessionInfo(int replicaId, String userId) {
    }

    public record PresenceBroadcast(
        int replicaId,
        String userId,
        String displayName,
        String color,
        Integer cursorReplicaId, Long cursorLamport,
        Integer anchorReplicaId, Long anchorLamport,
        boolean typing,
        boolean online
    ) {
    }

    public record PresenceUpdate(boolean full, List<PresenceBroadcast> entries, boolean complete) {
    }

    public record AccessChanged() {
    }

    public record ErrorMessage(String code, String message, long retryAfterMillis) {
        public ErrorMessage(String code, String message) {
            this(code, message, 0);
        }
    }
}
