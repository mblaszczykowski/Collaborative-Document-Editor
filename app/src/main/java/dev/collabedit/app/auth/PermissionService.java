package dev.collabedit.app.auth;

import dev.collabedit.app.cluster.ClusterBus;
import dev.collabedit.app.cluster.PermissionInvalidationBus;
import dev.collabedit.app.config.SchedulingConfig;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Kto co może zrobić z danym dokumentem (CP12).
 *
 * <h2>Kolejność rozstrzygania — i dlaczego akurat taka</h2>
 * <ol>
 *   <li><b>Właściciel</b> — zawsze {@link Role#OWNER}. Nie da się właścicielowi odebrać
 *       dostępu wpisem w tabeli, bo to on tymi wpisami zarządza.</li>
 *   <li><b>Wpis wprost dla dokumentu</b> — nadpisuje dziedziczenie. Bez tego nie dałoby się
 *       odebrać komuś dostępu do JEDNEGO dokumentu bez wyrzucania go z całego workspace'u.</li>
 *   <li><b>Członkostwo w workspace</b> — dziedziczone przez wszystkie jego dokumenty.</li>
 *   <li><b>Widoczność {@code link}</b> — {@link Role#EDITOR} dla każdego zalogowanego.</li>
 *   <li>W pozostałych przypadkach — <b>brak dostępu</b>.</li>
 * </ol>
 *
 * <h2>Dlaczego domyślną widocznością jest „link"</h2>
 * Bo dokładnie tak zachowywała się aplikacja przed CP12 i migracja nie ma prawa nikomu
 * odebrać dostępu do dokumentu, który wcześniej otwierał. Mechanizm jest przy tym realny:
 * przełączenie dokumentu na {@code private} natychmiast ogranicza dostęp do właściciela
 * i osób wymienionych wprost. Odpowiednik dokumentu Google z ustawieniem „każdy z linkiem
 * może edytować".
 *
 * <h2>Cache i jego unieważnianie</h2>
 * Sprawdzenie uprawnienia dzieje się przy KAŻDEJ ramce STOMP, więc zapytanie do bazy za
 * każdym razem byłoby zapytaniem na naciśnięcie klawisza. Wynik jest cache'owany na krótko —
 * na tyle krótko, żeby odebranie dostępu działało w czasie, który człowiek uzna za
 * „natychmiast", i na tyle długo, żeby baza nie widziała ruchu proporcjonalnego do pisania.
 */
@Service
public class PermissionService {

    private static final Logger log = LoggerFactory.getLogger(PermissionService.class);

    /**
     * Jak długo pamiętamy rozstrzygnięcie.
     *
     * <p>Pięć sekund jest kompromisem między dwoma niepożądanymi zjawiskami: zapytaniem
     * do bazy na każdą operację (przy zerowym TTL) i dostępem żyjącym po odebraniu
     * uprawnień (przy długim). Górna granica opóźnienia odebrania dostępu to ta wartość
     * — i jest to <b>jedyna</b> gwarancja, jakiej ten mechanizm udziela.
     */
    static final Duration CACHE_TTL = Duration.ofSeconds(5);

    private record CacheKey(String userId, UUID documentId) {
    }

    private record CachedRole(Optional<Role> role, long expiresAtMillis) {
    }

    private final Map<CacheKey, CachedRole> cache = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final PermissionInvalidationBus invalidations;

    public PermissionService(JdbcTemplate jdbc, PermissionInvalidationBus invalidations) {
        this.jdbc = jdbc;
        this.invalidations = invalidations;
    }

    /**
     * Subscribes to invalidations from the other instances.
     *
     * <p>The handler is registered here rather than the bus depending on this service, for the
     * same reason {@code DocumentService} wires itself to {@link ClusterBus}: the transport should
     * not know what the message means.
     */
    @jakarta.annotation.PostConstruct
    void subscribeToInvalidations() {
        invalidations.onInvalidate(this::invalidateLocally);
    }

    /**
     * Rola użytkownika wobec dokumentu; puste, gdy nie ma żadnego dostępu.
     *
     * <p>Zwracany {@code Optional} rozróżnia „nie ma dostępu" od „ma najniższy dostęp",
     * bo to są dwie różne odpowiedzi: pierwsza znaczy 404/403, druga — widok tylko do odczytu.
     */
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

    /**
     * Rola wobec dokumentu <b>usuniętego</b> — do obsługi kosza i przywracania.
     *
     * <p>Osobna metoda, bo {@link #roleOf} celowo traktuje dokument w koszu jak nieistniejący:
     * to jest właściwe zachowanie dla otwierania i edycji. Ale kosz i przywracanie muszą
     * z definicji operować na dokumentach usuniętych, a nie wolno im przy tym omijać
     * uprawnień — inaczej kosz stałby się listą cudzych tytułów.
     *
     * <p>Nie jest cache'owane: jest wołane przy wyświetlaniu kosza i przywracaniu, czyli
     * rzadko. Cache dokładałby tu unieważnianie bez zysku.
     */
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

    public boolean canWriteText(String userId, UUID documentId) {
        return roleOf(userId, documentId).filter(Role::canWriteText).isPresent();
    }

    public boolean canComment(String userId, UUID documentId) {
        return roleOf(userId, documentId).filter(Role::canComment).isPresent();
    }

    public boolean canManageAccess(String userId, UUID documentId) {
        return roleOf(userId, documentId).filter(Role::canManageAccess).isPresent();
    }

    /**
     * Jedno zapytanie zamiast czterech.
     *
     * <p>Rozstrzygnięcie wymaga danych z trzech tabel, ale nie ma powodu odpytywać ich
     * po kolei: przy sprawdzeniu wykonywanym na każdą ramkę trzy dodatkowe obiegi do bazy
     * są trzykrotnie większym opóźnieniem, a nie trzykrotnie lepszą czytelnością.
     */
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
            // Dokument nie istnieje albo jest w koszu. Brak dostępu, nie wyjątek —
            // z punktu widzenia autoryzacji to jest ta sama odpowiedź.
            return Optional.empty();
        }
        return roleFrom(userId, rows.getFirst());
    }

    /** Kolejność rozstrzygania z javadoca klasy, w jednym miejscu dla obu ścieżek. */
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

    // ------------------------------------------------------------------
    // Unieważnianie
    // ------------------------------------------------------------------

    /**
     * Drops the cached roles for a document, <b>on every instance</b>.
     *
     * <p>Called after every change to access. Without it, revoking a permission would only take
     * effect once the entry expired — and the person who has just removed somebody's access
     * expects that to have worked, not to be about to work.
     *
     * <h2>It used to stop at this instance, and that was the wrong shape of compromise</h2>
     * The previous version cleared the local map and documented the gap: on a cluster, the other
     * instances kept serving the old role until their own entry expired, up to {@link #CACHE_TTL}.
     * The note called the fix "broadcasting the invalidation over the Redis channel" and left it
     * as a known limitation.
     *
     * <p>It is worth being precise about why that limitation was different from the others in this
     * project. Almost everything here is built on the idea that replicas may disagree briefly and
     * converge — that is ADR-0001's whole argument, and ADR-0004 makes the cluster bus
     * deliberately unreliable on the same reasoning. <b>This is the one place where that does not
     * transfer.</b> A text operation arriving late is still the same document. A revocation
     * arriving late is a window during which the system keeps doing something it was told to stop
     * doing. Same system, same Redis, opposite requirement.
     *
     * <p>The broadcast is still best-effort, and that is deliberate rather than overlooked: the
     * TTL is the backstop. A lost message degrades to precisely the old behaviour — bounded by
     * five seconds — instead of to something unbounded. See {@link PermissionInvalidationBus} for
     * why a durable channel would be machinery out of proportion to a window that already has a
     * floor.
     */
    public void invalidate(UUID documentId) {
        invalidateLocally(documentId);
        invalidations.publish(documentId);
    }

    /**
     * Drops the cached roles for a document on this instance only.
     *
     * <p>The receiving half of {@link #invalidate}. Separate so that applying a message from
     * another instance does not rebroadcast it, which would be a loop the deduplication in
     * {@link PermissionInvalidationBus} only half prevents.
     */
    void invalidateLocally(UUID documentId) {
        cache.keySet().removeIf(key -> key.documentId().equals(documentId));
        log.debug("Dropped cached permissions for document {}", documentId);
    }

    public void invalidateAll() {
        cache.clear();
    }

    /**
     * Upper bound on the number of cached decisions.
     *
     * <p>Expiry alone does not bound anything, and that is the whole reason this exists.
     * {@link #roleOf} checks the timestamp when it <b>reads</b> an entry and replaces it — but an
     * entry nobody reads again is never touched, so the map kept one row per
     * {@code (user, document)} pair ever seen, for the lifetime of the process.
     *
     * <p>{@code application.yml} already makes this argument, about a different cache:
     * <i>"the idle threshold does not bound anything with many documents — a hundred people
     * opening a hundred different documents in a minute gives a hundred replicas, none of them
     * idle. A time limit is not a memory limit."</i> The identical argument applies here and had
     * not been made.
     *
     * <p>20 000 entries is roughly a megabyte of small records, and it is a <b>backstop</b> rather
     * than an operating point: the sweep below keeps the real size at "distinct pairs seen in the
     * last minute", which normal traffic keeps far below this. Reaching it means something is
     * enumerating documents, and dropping the cache is the correct response to that — the cost is
     * one database query per subsequent check, not an error.
     */
    private static final int MAX_ENTRIES = 20_000;

    /**
     * Removes entries whose TTL has passed.
     *
     * <p>On the maintenance pool, not the flow pool: this walks the whole map, and the flow pool
     * is the one that must broadcast every 10 ms ({@link SchedulingConfig}).
     *
     * <p>Every minute rather than every five seconds. The sweep is not what makes expiry correct
     * — {@link #roleOf} already refuses to serve a stale entry — so its only job is to stop dead
     * entries accumulating. Running it at the TTL would walk the map twelve times more often to
     * reclaim the same memory a few seconds sooner.
     */
    @Scheduled(
        fixedDelayString = "${collabedit.auth.permission-cache-sweep-ms:60000}",
        scheduler = SchedulingConfig.MAINTENANCE)
    void sweepExpiredRoles() {
        long now = System.currentTimeMillis();
        int before = cache.size();
        cache.values().removeIf(cached -> cached.expiresAtMillis() <= now);

        if (cache.size() > MAX_ENTRIES) {
            // Everything left is unexpired, so there is nothing cheaper to drop. Clearing costs a
            // database query per subsequent check and nothing else — the cache is an
            // optimisation, and correctness never depended on a hit.
            log.warn("Permission cache holds {} live entries, above the {} bound — clearing. "
                    + "Something is touching an unusual number of documents.",
                cache.size(), MAX_ENTRIES);
            cache.clear();
        } else if (before != cache.size()) {
            log.debug("Permission cache: dropped {} expired entries, {} remain",
                before - cache.size(), cache.size());
        }
    }

    // ------------------------------------------------------------------
    // Zarządzanie dostępem
    // ------------------------------------------------------------------

    public void grant(UUID documentId, String userId, Role role) {
        jdbc.update("""
            INSERT INTO document_members (doc_id, user_id, role) VALUES (?, ?, ?)
            ON CONFLICT (doc_id, user_id) DO UPDATE SET role = EXCLUDED.role
            """, documentId, userId, role.wire());
        invalidate(documentId);
    }

    public void revoke(UUID documentId, String userId) {
        jdbc.update("DELETE FROM document_members WHERE doc_id = ? AND user_id = ?",
            documentId, userId);
        invalidate(documentId);
    }

    public void setVisibility(UUID documentId, String visibility) {
        if (!"private".equals(visibility) && !"link".equals(visibility)) {
            throw new IllegalArgumentException("nieznana widoczność: " + visibility);
        }
        jdbc.update("UPDATE documents SET visibility = ? WHERE id = ?", visibility, documentId);
        invalidate(documentId);
    }

    public void setOwner(UUID documentId, String ownerId) {
        jdbc.update("UPDATE documents SET owner_id = ? WHERE id = ?", ownerId, documentId);
        invalidate(documentId);
    }

    /**
     * A document's access settings, as opposed to one user's role in it (CP21).
     *
     * <p>Separate from {@link #roleOf} because it answers the owner's question ("who can get in
     * here") rather than the request path's question ("may this caller do this"). The first is
     * asked once, when a share panel opens; the second on every operation, which is why one is
     * cached and the other deliberately is not.
     */
    public record Settings(String ownerId, String visibility) {
    }

    public Optional<Settings> settingsOf(UUID documentId) {
        return jdbc.query("""
            SELECT owner_id, visibility FROM documents WHERE id = ? AND deleted_at IS NULL
            """,
            (rs, rowNum) -> new Settings(rs.getString("owner_id"), rs.getString("visibility")),
            documentId).stream().findFirst();
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
