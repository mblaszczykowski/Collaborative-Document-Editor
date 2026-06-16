package dev.collabedit.app.ws;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The token bucket that limits frame rate (CP12).
 *
 * <p>Time is supplied from outside, so the test does not sleep and does not depend on the machine's
 * clock — otherwise it would be checking whether {@code Thread.sleep} is punctual rather than
 * whether the limit works.
 *
 * <table>
 *   <caption>Test -> failure mode</caption>
 *   <tr><th>Test</th><th>What it catches</th></tr>
 *   <tr><td>{@code burstIsAllowedThenThrottled}</td>
 *       <td>A limit with no burst. Pasting a paragraph and retransmitting the buffer after coming
 *           back from offline are legitimate situations in which a client sends a lot at once — a
 *           limit that refuses them breaks a working feature.</td></tr>
 *   <tr><td>{@code tokensRefillOverTime}</td>
 *       <td>A bucket that never refills. A session limited once stays limited forever, so the user
 *           has to reload the page to keep typing.</td></tr>
 *   <tr><td>{@code sessionsDoNotShareBudget}</td>
 *       <td>One shared bucket for everyone. A single active client silences the rest — that is, a
 *           limit meant to protect the server becomes a way to attack other users.</td></tr>
 *   <tr><td>{@code presenceAndOperationsHaveSeparateBudgets}</td>
 *       <td>One bucket for both kinds of traffic. Presence is several times more frequent, so it
 *           would eat the operation budget — and typing would stop working from moving the cursor
 *           alone.</td></tr>
 *   <tr><td>{@code forgottenSessionStartsFresh}</td>
 *       <td>A memory leak: the bucket maps grow with the number of sessions since process start.</td></tr>
 * </table>
 */
class RateLimiterTest {

    private final RateLimiter limiter = new RateLimiter();

    @Test
    @DisplayName("a burst is let through, and only then does throttling begin")
    void burstIsAllowedThenThrottled() {
        int allowed = 0;
        for (int i = 0; i < 500; i++) {
            if (limiter.allowOperation("s1", 1000).allowed()) {
                allowed++;
            }
        }
        assertThat(allowed)
            .as("the burst has to fit pasting a paragraph and retransmitting the offline buffer")
            .isGreaterThanOrEqualTo(200)
            .isLessThan(500);
    }

    @Test
    @DisplayName("tokens come back as time passes")
    void tokensRefillOverTime() {
        while (limiter.allowOperation("s1", 1000).allowed()) {
            // drain the bucket
        }
        assertThat(limiter.allowOperation("s1", 1000).allowed()).isFalse();

        assertThat(limiter.allowOperation("s1", 1100).allowed())
            .as("after 100 ms the bucket recovered no tokens — the session is blocked for good")
            .isTrue();
    }

    @Test
    @DisplayName("sessions do not share a budget")
    void sessionsDoNotShareBudget() {
        while (limiter.allowOperation("noisy", 1000).allowed()) {
            // exhaust one session's budget
        }

        assertThat(limiter.allowOperation("quiet", 1000).allowed())
            .as("one active session silenced the others — the limit became an attack tool")
            .isTrue();
    }

    @Test
    @DisplayName("presence and operations have separate budgets")
    void presenceAndOperationsHaveSeparateBudgets() {
        while (limiter.allowPresence("s1", 1000).allowed()) {
            // exhaust the presence budget
        }

        assertThat(limiter.allowOperation("s1", 1000).allowed())
            .as("cursor movement ate the typing budget — moving the cursor blocks editing")
            .isTrue();
    }

    @Test
    @DisplayName("a forgotten session starts from a full bucket")
    void forgottenSessionStartsFresh() {
        while (limiter.allowOperation("s1", 1000).allowed()) {
            // drain it
        }
        limiter.forget("s1");

        assertThat(limiter.allowOperation("s1", 1000).allowed()).isTrue();
    }

    @Test
    @DisplayName("forgetting a session releases memory")
    void forgettingReleasesMemory() {
        limiter.allowOperation("s1", 1000).allowed();
        limiter.allowPresence("s1", 1000).allowed();
        assertThat(limiter.trackedBuckets()).isEqualTo(2);

        limiter.forget("s1");

        assertThat(limiter.trackedBuckets())
            .as("the bucket maps grow with the number of sessions since process start")
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
