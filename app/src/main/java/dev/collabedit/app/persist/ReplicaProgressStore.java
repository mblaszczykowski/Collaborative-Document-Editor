package dev.collabedit.app.persist;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import dev.collabedit.crdt.VersionVector;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ReplicaProgressStore {
    private static final Logger log = LoggerFactory.getLogger(ReplicaProgressStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Long>> VERSION_SHAPE = new TypeReference<>() { };

    private final JdbcTemplate jdbc;

    public ReplicaProgressStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(UUID documentId, int replicaId, VersionVector version) {
        String json = encode(version);
        jdbc.update("""
            INSERT INTO replica_progress (doc_id, replica_id, version, reported_at, departed_at)
                 VALUES (?, ?, ?::jsonb, now(), NULL)
            ON CONFLICT (doc_id, replica_id) DO UPDATE
                    SET version     = EXCLUDED.version,
                        reported_at = EXCLUDED.reported_at,
                        departed_at = NULL
            """, documentId, replicaId, json);
    }

    public void markDeparted(UUID documentId, int replicaId) {
        jdbc.update("""
            UPDATE replica_progress
               SET departed_at = now()
             WHERE doc_id = ? AND replica_id = ? AND departed_at IS NULL
            """, documentId, replicaId);
    }

    public List<VersionVector> liveFor(UUID documentId, long unresponsiveTimeoutMs,
                                       long offlineGraceMillis) {
        List<String> rows = jdbc.query("""
            SELECT version::text AS version
              FROM replica_progress
             WHERE doc_id = ?
               AND ((departed_at IS     NULL AND reported_at > now() - make_interval(secs => ?))
                 OR (departed_at IS NOT NULL AND departed_at > now() - make_interval(secs => ?)))
            """,
            (rs, rowNum) -> rs.getString("version"),
            documentId, Math.max(unresponsiveTimeoutMs, offlineGraceMillis) / 1000.0,
            offlineGraceMillis / 1000.0);

        List<VersionVector> live = new ArrayList<>(rows.size());
        for (String row : rows) {
            VersionVector decoded = decode(row);
            if (decoded != null) {
                live.add(decoded);
            }
        }
        return live;
    }

    public int pruneExpired(long offlineGraceMillis) {
        return jdbc.update("""
            DELETE FROM replica_progress
             WHERE (departed_at IS NOT NULL AND departed_at <= now() - make_interval(secs => ?))
                OR (departed_at IS     NULL AND reported_at <= now() - make_interval(secs => ?))
            """, offlineGraceMillis / 1000.0, abandonedAfterSeconds(offlineGraceMillis));
    }

    private static double abandonedAfterSeconds(long offlineGraceMillis) {
        return (offlineGraceMillis / 1000.0) * 2;
    }

    private static String encode(VersionVector version) {
        try {
            return MAPPER.writeValueAsString(version.asMap());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a version vector could not be serialised", e);
        }
    }

    private static VersionVector decode(String json) {
        try {
            Map<Integer, Long> parsed = new java.util.HashMap<>();
            MAPPER.readValue(json, VERSION_SHAPE)
                .forEach((replicaId, lamport) -> parsed.put(Integer.valueOf(replicaId), lamport));
            return VersionVector.of(parsed);
        } catch (JsonProcessingException | NumberFormatException e) {
            log.warn("Unreadable replica progress row, ignored for causal stability: {}",
                e.toString());
            return null;
        }
    }
}
