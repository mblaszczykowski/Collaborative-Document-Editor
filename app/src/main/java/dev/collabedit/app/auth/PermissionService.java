package dev.collabedit.app.auth;

import dev.collabedit.app.cluster.ClusterBus;
import dev.collabedit.app.cluster.PermissionInvalidationBus;
import dev.collabedit.app.config.SchedulingConfig;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class PermissionService {
    private static final Logger log = LoggerFactory.getLogger(PermissionService.class);

    static final Duration CACHE_TTL = Duration.ofSeconds(5);

    private record CacheKey(String userId, UUID documentId) {
    }

    private record CachedRole(Optional<Role> role, long expiresAtMillis) {
    }

    private final Map<CacheKey, CachedRole> cache = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final PermissionInvalidationBus invalidations;

    private volatile Consumer<UUID> onAccessChanged = documentId -> { };

    public PermissionService(JdbcTemplate jdbc, PermissionInvalidationBus invalidations) {
        this.jdbc = jdbc;
        this.invalidations = invalidations;
    }

    public void onAccessChanged(Consumer<UUID> handler) {
        this.onAccessChanged = handler;
    }

    @PostConstruct
    void subscribeToInvalidations() {
        invalidations.onInvalidate(this::invalidateLocally);
    }

    public Optional<Role> roleOf(String userId, UUID documentId) {
        if (userId == null || documentId == null) {
            return Optional.empty();
        }
        CacheKey key = new CacheKey(userId, documentId);
        long now = System.currentTimeMillis();

        CachedRole cached = cache.get(key);
        if (cached != null && cached.expiresAtMillis() > now) {
            return cached.role();
        }
        Optional<Role> resolved = resolve(userId, documentId);
        cache.put(key, new CachedRole(resolved, now + CACHE_TTL.toMillis()));
        return resolved;
    }

    public boolean canRead(String userId, UUID documentId) {
        return roleOf(userId, documentId).isPresent();
    }

    public Optional<Role> roleOfDeleted(String userId, UUID documentId) {
        if (userId == null || documentId == null) {
            return Optional.empty();
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT d.owner_id,
                   d.visibility,
                   dm.role AS document_role,
                   wm.role AS workspace_role
              FROM documents d
              LEFT JOIN document_members  dm ON dm.doc_id       = d.id           AND dm.user_id = ?
              LEFT JOIN workspace_members wm ON wm.workspace_id = d.workspace_id AND wm.user_id = ?
             WHERE d.id = ?
            """, userId, userId, documentId);
        return rows.isEmpty() ? Optional.empty() : roleFrom(userId, rows.getFirst());
    }

    private Optional<Role> resolve(String userId, UUID documentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT d.owner_id,
                   d.visibility,
                   dm.role AS document_role,
                   wm.role AS workspace_role
              FROM documents d
              LEFT JOIN document_members  dm ON dm.doc_id       = d.id           AND dm.user_id = ?
              LEFT JOIN workspace_members wm ON wm.workspace_id = d.workspace_id AND wm.user_id = ?
             WHERE d.id = ? AND d.deleted_at IS NULL
            """, userId, userId, documentId);

        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return roleFrom(userId, rows.getFirst());
    }

    private static Optional<Role> roleFrom(String userId, Map<String, Object> row) {
        if (userId.equals(row.get("owner_id"))) {
            return Optional.of(Role.OWNER);
        }
        if (row.get("document_role") != null) {
            return Optional.of(Role.parse((String) row.get("document_role")));
        }
        if (row.get("workspace_role") != null) {
            return Optional.of(Role.parse((String) row.get("workspace_role")));
        }
        if ("link".equals(row.get("visibility"))) {
            return Optional.of(Role.EDITOR);
        }
        return Optional.empty();
    }

    public void invalidate(UUID documentId) {
        invalidateLocally(documentId);
        invalidations.publish(documentId);
    }

    void invalidateLocally(UUID documentId) {
        cache.keySet().removeIf(key -> key.documentId().equals(documentId));
        log.debug("Dropped cached permissions for document {}", documentId);
        try {
            onAccessChanged.accept(documentId);
        } catch (RuntimeException e) {
            log.warn("Failed to announce the access change for document {}: {}", documentId,
                e.toString());
        }
    }

    private static final int MAX_ENTRIES = 20_000;

    @Scheduled(
        fixedDelayString = "${collabedit.auth.permission-cache-sweep-ms:60000}",
        scheduler = SchedulingConfig.MAINTENANCE)
    void sweepExpiredRoles() {
        long now = System.currentTimeMillis();
        int before = cache.size();
        cache.values().removeIf(cached -> cached.expiresAtMillis() <= now);

        if (cache.size() > MAX_ENTRIES) {
            log.warn("Permission cache holds {} live entries, above the {} bound - clearing. "
                    + "Something is touching an unusual number of documents.",
                cache.size(), MAX_ENTRIES);
            cache.clear();
        } else if (before != cache.size()) {
            log.debug("Permission cache: dropped {} expired entries, {} remain",
                before - cache.size(), cache.size());
        }
    }

    public void grant(UUID documentId, String userId, Role role) {
        jdbc.update("""
            INSERT INTO document_members (doc_id, user_id, role) VALUES (?, ?, ?)
            ON CONFLICT (doc_id, user_id) DO UPDATE SET role = EXCLUDED.role
            """, documentId, userId, role.wire());
        invalidate(documentId);
    }

    public boolean revoke(UUID documentId, String userId) {
        int removed = jdbc.update("DELETE FROM document_members WHERE doc_id = ? AND user_id = ?",
            documentId, userId);
        invalidate(documentId);
        return removed > 0;
    }

    public void setVisibility(UUID documentId, String visibility) {
        if (!"private".equals(visibility) && !"link".equals(visibility)) {
            throw new IllegalArgumentException("unknown visibility: " + visibility);
        }
        jdbc.update("UPDATE documents SET visibility = ? WHERE id = ?", visibility, documentId);
        invalidate(documentId);
    }

    public void setOwner(UUID documentId, String ownerId) {
        jdbc.update("UPDATE documents SET owner_id = ? WHERE id = ?", ownerId, documentId);
        invalidate(documentId);
    }

    public record Settings(String ownerId, String visibility) {
    }

    public Optional<Settings> settingsOf(UUID documentId) {
        return jdbc.query("""
            SELECT owner_id, visibility FROM documents WHERE id = ? AND deleted_at IS NULL
            """,
            (rs, rowNum) -> new Settings(rs.getString("owner_id"), rs.getString("visibility")),
            documentId).stream().findFirst();
    }

    public boolean userExists(String userId) {
        return userId != null && !jdbc.queryForList(
            "SELECT 1 FROM users WHERE id = ?", Integer.class, userId).isEmpty();
    }

    public record Member(String userId, String displayName, Role role) {
    }

    public List<Member> membersOf(UUID documentId) {
        return jdbc.query("""
            SELECT u.id, u.display_name, dm.role
              FROM document_members dm
              JOIN users u ON u.id = dm.user_id
             WHERE dm.doc_id = ?
             ORDER BY u.display_name
            """,
            (rs, rowNum) -> new Member(
                rs.getString("id"), rs.getString("display_name"), Role.parse(rs.getString("role"))),
            documentId);
    }
}
