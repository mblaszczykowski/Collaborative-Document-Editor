package dev.collabedit.app.auth;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
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

    public PermissionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
     * Unieważnia zapamiętane rozstrzygnięcia dla dokumentu.
     *
     * <p>Wołane po każdej zmianie dostępu. Bez tego odebranie uprawnień działałoby dopiero
     * po wygaśnięciu wpisu — a użytkownik, który właśnie komuś dostęp odebrał, oczekuje,
     * że to zadziałało, a nie że zadziała.
     *
     * <p><b>Ograniczenie:</b> unieważnienie jest LOKALNE dla instancji. Przy wielu instancjach
     * pozostałe zobaczą zmianę dopiero po wygaśnięciu wpisu, czyli w granicy
     * {@link #CACHE_TTL}. Pełne rozwiązanie to rozgłoszenie unieważnienia kanałem Redisa —
     * tym samym, którym idą operacje. Wymienione w README jako znana granica.
     */
    public void invalidate(UUID documentId) {
        cache.keySet().removeIf(key -> key.documentId().equals(documentId));
        log.debug("Unieważniono zapamiętane uprawnienia dokumentu {}", documentId);
    }

    public void invalidateAll() {
        cache.clear();
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
