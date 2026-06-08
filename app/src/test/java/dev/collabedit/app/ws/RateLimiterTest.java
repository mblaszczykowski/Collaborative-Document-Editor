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
            if (limiter.allowOperation("s1", 1000)) {
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
        while (limiter.allowOperation("s1", 1000)) {
            // opróżniamy wiadro
        }
        assertThat(limiter.allowOperation("s1", 1000)).isFalse();

        assertThat(limiter.allowOperation("s1", 1100))
            .as("po 100 ms wiadro nie odzyskało ani jednego żetonu — sesja jest zablokowana na stałe")
            .isTrue();
    }

    @Test
    @DisplayName("sesje nie dzielą budżetu")
    void sessionsDoNotShareBudget() {
        while (limiter.allowOperation("hałaśliwa", 1000)) {
            // wyczerpujemy budżet jednej sesji
        }

        assertThat(limiter.allowOperation("spokojna", 1000))
            .as("jedna aktywna sesja wyciszyła pozostałe — limit stał się narzędziem ataku")
            .isTrue();
    }

    @Test
    @DisplayName("obecność i operacje mają osobne budżety")
    void presenceAndOperationsHaveSeparateBudgets() {
        while (limiter.allowPresence("s1", 1000)) {
            // wyczerpujemy budżet obecności
        }

        assertThat(limiter.allowOperation("s1", 1000))
            .as("ruch kursora zjadł budżet pisania — poruszanie kursorem blokuje edycję")
            .isTrue();
    }

    @Test
    @DisplayName("zapomniana sesja zaczyna od pełnego wiadra")
    void forgottenSessionStartsFresh() {
        while (limiter.allowOperation("s1", 1000)) {
            // opróżniamy
        }
        limiter.forget("s1");

        assertThat(limiter.allowOperation("s1", 1000)).isTrue();
    }

    @Test
    @DisplayName("zapominanie sesji zwalnia pamięć")
    void forgettingReleasesMemory() {
        limiter.allowOperation("s1", 1000);
        limiter.allowPresence("s1", 1000);
        assertThat(limiter.trackedSessions()).isEqualTo(2);

        limiter.forget("s1");

        assertThat(limiter.trackedSessions())
            .as("mapy wiader rosną z liczbą sesji od startu procesu")
            .isZero();
    }
}
