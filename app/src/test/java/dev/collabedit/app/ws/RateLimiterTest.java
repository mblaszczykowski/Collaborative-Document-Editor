package dev.collabedit.app.ws;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Wiadro żetonów ograniczające tempo ramek (CP12).
 *
 * <p>Czas jest podawany z zewnątrz, więc test nie śpi i nie zależy od zegara maszyny —
 * inaczej sprawdzałby, czy {@code Thread.sleep} jest punktualny, a nie czy limit działa.
 *
 * <table>
 *   <caption>Test → failure mode</caption>
 *   <tr><th>Test</th><th>Co wyłapuje</th></tr>
 *   <tr><td>{@code burstIsAllowedThenThrottled}</td>
 *       <td>Limit bez zrywu. Wklejenie akapitu i retransmisja bufora po powrocie z trybu
 *           offline to legalne sytuacje, w których klient wysyła dużo naraz — limit,
 *           który je odrzuca, psuje działającą funkcję.</td></tr>
 *   <tr><td>{@code tokensRefillOverTime}</td>
 *       <td>Wiadro, które się nie napełnia. Sesja raz ograniczona zostaje ograniczona
 *           na zawsze, więc użytkownik musi odświeżyć stronę, żeby pisać dalej.</td></tr>
 *   <tr><td>{@code sessionsDoNotShareBudget}</td>
 *       <td>Wspólne wiadro dla wszystkich. Jeden aktywny klient wycisza pozostałych —
 *           czyli limit chroniący serwer staje się sposobem na atak na innych.</td></tr>
 *   <tr><td>{@code presenceAndOperationsHaveSeparateBudgets}</td>
 *       <td>Jedno wiadro na oba rodzaje ruchu. Obecność jest kilkukrotnie częstsza,
 *           więc zjadałaby budżet operacji — i pisanie przestawałoby działać przy
 *           samym poruszaniu kursorem.</td></tr>
 *   <tr><td>{@code forgottenSessionStartsFresh}</td>
 *       <td>Wyciek pamięci: mapy wiader rosną z liczbą sesji od startu procesu.</td></tr>
 * </table>
 */
class RateLimiterTest {

    private final RateLimiter limiter = new RateLimiter();

    @Test
    @DisplayName("zryw jest przepuszczany, dopiero potem wchodzi ograniczenie")
    void burstIsAllowedThenThrottled() {
        int allowed = 0;
        for (int i = 0; i < 500; i++) {
            if (limiter.allowOperation("s1", 1000).allowed()) {
                allowed++;
            }
        }
        assertThat(allowed)
            .as("zryw musi zmieścić wklejenie akapitu i retransmisję bufora offline")
            .isGreaterThanOrEqualTo(200)
            .isLessThan(500);
    }

    @Test
    @DisplayName("żetony wracają wraz z upływem czasu")
    void tokensRefillOverTime() {
        while (limiter.allowOperation("s1", 1000).allowed()) {
            // opróżniamy wiadro
        }
        assertThat(limiter.allowOperation("s1", 1000).allowed()).isFalse();

        assertThat(limiter.allowOperation("s1", 1100).allowed())
            .as("po 100 ms wiadro nie odzyskało ani jednego żetonu — sesja jest zablokowana na stałe")
            .isTrue();
    }

    @Test
    @DisplayName("sesje nie dzielą budżetu")
    void sessionsDoNotShareBudget() {
        while (limiter.allowOperation("hałaśliwa", 1000).allowed()) {
            // wyczerpujemy budżet jednej sesji
        }

        assertThat(limiter.allowOperation("spokojna", 1000).allowed())
            .as("jedna aktywna sesja wyciszyła pozostałe — limit stał się narzędziem ataku")
            .isTrue();
    }

    @Test
    @DisplayName("obecność i operacje mają osobne budżety")
    void presenceAndOperationsHaveSeparateBudgets() {
        while (limiter.allowPresence("s1", 1000).allowed()) {
            // wyczerpujemy budżet obecności
        }

        assertThat(limiter.allowOperation("s1", 1000).allowed())
            .as("ruch kursora zjadł budżet pisania — poruszanie kursorem blokuje edycję")
            .isTrue();
    }

    @Test
    @DisplayName("zapomniana sesja zaczyna od pełnego wiadra")
    void forgottenSessionStartsFresh() {
        while (limiter.allowOperation("s1", 1000).allowed()) {
            // opróżniamy
        }
        limiter.forget("s1");

        assertThat(limiter.allowOperation("s1", 1000).allowed()).isTrue();
    }

    @Test
    @DisplayName("zapominanie sesji zwalnia pamięć")
    void forgettingReleasesMemory() {
        limiter.allowOperation("s1", 1000).allowed();
        limiter.allowPresence("s1", 1000).allowed();
        assertThat(limiter.trackedBuckets()).isEqualTo(2);

        limiter.forget("s1");

        assertThat(limiter.trackedBuckets())
            .as("mapy wiader rosną z liczbą sesji od startu procesu")
            .isZero();
    }

    // ------------------------------------------------------------------
    // CP20 — the refusal says how long to wait
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a refused frame comes with the exact wait, not a guess")
    void refusalCarriesTheTimeUntilTheNextToken() {
        RateLimiter limiter = new RateLimiter();
        while (limiter.allowOperation("s1", 1000).allowed()) {
            // drain the burst
        }

        RateLimiter.Decision refused = limiter.allowOperation("s1", 1000);

        assertThat(refused.allowed()).isFalse();
        // The bucket refills at 60/s, so one token is 1000/60 = 16.67 ms away, rounded up.
        // The client used to wait a hardcoded 1000 ms for this — sixty times too long, and
        // that difference is what the README's p99 of 1299 ms is made of.
        assertThat(refused.retryAfterMillis())
            .as("the wait the server already knows is still being guessed by the client")
            .isBetween(1L, 20L);
    }

    @Test
    @DisplayName("waiting exactly as long as told is enough")
    void waitingTheAdvertisedTimeIsEnough() {
        // If this were off by even a millisecond in the wrong direction, honest clients would
        // retry into a second refusal and the mechanism would amplify the load it exists to
        // reduce — the failure mode is worse than the one it replaces.
        RateLimiter limiter = new RateLimiter();
        while (limiter.allowOperation("s1", 5000).allowed()) {
            // drain the burst
        }
        long wait = limiter.allowOperation("s1", 5000).retryAfterMillis();

        assertThat(limiter.allowOperation("s1", 5000 + wait).allowed())
            .as("the advertised wait was too short, so a well-behaved client is refused twice")
            .isTrue();
    }

    @Test
    @DisplayName("an allowed frame advertises no wait")
    void allowedFrameCarriesNoWait() {
        RateLimiter limiter = new RateLimiter();
        assertThat(limiter.allowOperation("s1", 1000).retryAfterMillis()).isZero();
    }
}
