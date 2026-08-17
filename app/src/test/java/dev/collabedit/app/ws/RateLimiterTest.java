package dev.collabedit.app.ws;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
        }
        assertThat(limiter.allowOperation("s1", 1000).allowed()).isFalse();

        assertThat(limiter.allowOperation("s1", 1100).allowed())
            .as("after 100 ms the bucket recovered no tokens - the session is blocked for good")
            .isTrue();
    }

    @Test
    @DisplayName("sessions do not share a budget")
    void sessionsDoNotShareBudget() {
        while (limiter.allowOperation("noisy", 1000).allowed()) {
        }

        assertThat(limiter.allowOperation("quiet", 1000).allowed())
            .as("one active session silenced the others - the limit became an attack tool")
            .isTrue();
    }

    @Test
    @DisplayName("presence and operations have separate budgets")
    void presenceAndOperationsHaveSeparateBudgets() {
        while (limiter.allowPresence("s1", 1000).allowed()) {
        }

        assertThat(limiter.allowOperation("s1", 1000).allowed())
            .as("cursor movement ate the typing budget - moving the cursor blocks editing")
            .isTrue();
    }

    @Test
    @DisplayName("a forgotten session starts from a full bucket")
    void forgottenSessionStartsFresh() {
        while (limiter.allowOperation("s1", 1000).allowed()) {
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

    @Test
    @DisplayName("a refused frame comes with the exact wait, not a guess")
    void refusalCarriesTheTimeUntilTheNextToken() {
        RateLimiter limiter = new RateLimiter();
        while (limiter.allowOperation("s1", 1000).allowed()) {
        }

        RateLimiter.Decision refused = limiter.allowOperation("s1", 1000);

        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterMillis())
            .as("the wait the server already knows is still being guessed by the client")
            .isBetween(1L, 20L);
    }

    @Test
    @DisplayName("waiting exactly as long as told is enough")
    void waitingTheAdvertisedTimeIsEnough() {
        RateLimiter limiter = new RateLimiter();
        while (limiter.allowOperation("s1", 5000).allowed()) {
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
