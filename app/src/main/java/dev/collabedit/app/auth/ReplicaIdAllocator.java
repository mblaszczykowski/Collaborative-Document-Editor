package dev.collabedit.app.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Globally durable client identities. Redis availability cannot change their ownership. */
@Service
public class ReplicaIdAllocator {
    private final JdbcTemplate jdbc;
    private final java.util.concurrent.ConcurrentHashMap<Integer, String> ownerCache = new java.util.concurrent.ConcurrentHashMap<>();

    public ReplicaIdAllocator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public int nextFor(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("A replica must belong to an authenticated account.");
        }
        Integer id = jdbc.queryForObject("SELECT nextval('client_replica_id_seq')", Integer.class);
        if (id == null) {
            throw new IllegalStateException("No replica identity was allocated.");
        }
        jdbc.update("INSERT INTO client_replica_owners (replica_id, user_id) VALUES (?, ?)", id, userId);
        return id;
    }

    public boolean owns(String userId, int replicaId) {
        if (userId == null || replicaId <= 0) { return false; }
        String cached = ownerCache.get(replicaId);
        if (cached != null) { return cached.equals(userId); }
        var owners = jdbc.queryForList("SELECT user_id FROM client_replica_owners WHERE replica_id = ? AND user_id IS NOT NULL",
            String.class, replicaId);
        if (owners.isEmpty()) { return false; }
        String owner = owners.getFirst();
        remember(replicaId, owner);
        return owner.equals(userId);
    }

    private void remember(int replicaId, String userId) {
        // Ownership never changes. Eviction affects database traffic, never authorization.
        if (ownerCache.size() >= 20_000) { ownerCache.clear(); }
        ownerCache.put(replicaId, userId);
    }
}
