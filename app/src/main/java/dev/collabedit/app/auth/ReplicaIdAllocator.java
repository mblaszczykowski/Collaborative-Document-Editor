package dev.collabedit.app.auth;

import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Przydział globalnie unikalnych identyfikatorów replik.
 *
 * <p>Unikalność jest wymogiem poprawności, nie wygody: dwie repliki o tym samym numerze
 * generowałyby kolidujące identyfikatory znaków, a algorytm z ADR-0001 uznałby je za ten
 * sam znak. Skutkiem byłoby ciche zgubienie tekstu jednego z autorów — błąd niewykrywalny
 * po fakcie, bo w strukturze nie zostaje po nim ślad.
 *
 * <p>Stąd jedno źródło numerów dla całego klastra: licznik w Redisie. Jest to jedyne miejsce,
 * w którym Redis pełni rolę <b>autorytatywną</b>, a nie ścieżki najlepszego wysiłku
 * (ADR-0004) — i dlatego jego niedostępność jest tu traktowana inaczej niż przy publikacji
 * operacji.
 */
@Service
public class ReplicaIdAllocator {

    private static final Logger log = LoggerFactory.getLogger(ReplicaIdAllocator.class);
    private static final String KEY = "collabedit:replica-id-seq";

    private final StringRedisTemplate redis;

    /**
     * Zapas awaryjny na wypadek niedostępności Redisa.
     *
     * <p>Punkt startowy jest losowy z bardzo dużego zakresu, więc kolizja z numerami
     * z Redisa albo z innej instancji jest skrajnie mało prawdopodobna — ale
     * <b>nie jest niemożliwa</b>. To jest świadomie gorsza gwarancja przyjęta po to, żeby
     * awaria Redisa oznaczała degradację, a nie odmowę obsługi. Zapisane wprost, bo różnica
     * między „unikalne" a „prawie na pewno unikalne" jest tu istotna.
     */
    private final AtomicInteger fallback =
        new AtomicInteger(new java.security.SecureRandom().nextInt(1, Integer.MAX_VALUE / 2));

    public ReplicaIdAllocator(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public int next() {
        try {
            Long value = redis.opsForValue().increment(KEY);
            if (value != null) {
                // Identyfikatory replik są dodatnie; instancje backendu używają ujemnych
                // (DocumentRegistry), więc przestrzenie nie mogą się przeciąć.
                return (int) (value % (Integer.MAX_VALUE / 2)) + 1;
            }
        } catch (RuntimeException e) {
            log.warn("Redis niedostępny przy przydziale identyfikatora repliki — używam zapasu: {}",
                e.toString());
        }
        return fallback.incrementAndGet();
    }
}
