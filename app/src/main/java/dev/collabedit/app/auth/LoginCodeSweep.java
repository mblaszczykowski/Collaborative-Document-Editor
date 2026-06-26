package dev.collabedit.app.auth;

import dev.collabedit.app.config.SchedulingConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Clears out login codes nobody spent.
 *
 * <h2>Why this is a separate class rather than a method on the store</h2>
 * {@link LoginCodeStore} answers questions about one code and knows nothing about clocks or thread
 * pools; scheduling is a property of the application, not of the table. Keeping them apart is also
 * what lets the store be tested without a scheduler running underneath it.
 *
 * <p>It is on the <b>maintenance</b> pool for the reason {@code SchedulingConfig} gives: the flow
 * pool is single-threaded on purpose and carries the 10 ms broadcast, and nothing here is urgent.
 *
 * <h2>Why it is needed at all, when redeeming already checks the age</h2>
 * It is not needed for correctness — {@code redeem} refuses an expired row whether or not anything
 * has removed it, which is the same discipline {@code InviteService} applies to invitations. What
 * it is needed for is the table staying small, and the case that fills it is ordinary rather than
 * exceptional: a sign-in abandoned at the provider's consent screen leaves a row that nobody will
 * ever come back for.
 */
@Component
public class LoginCodeSweep {

    private static final Logger log = LoggerFactory.getLogger(LoginCodeSweep.class);

    private final LoginCodeStore codes;

    public LoginCodeSweep(LoginCodeStore codes) {
        this.codes = codes;
    }

    /**
     * Every five minutes, against a one-minute lifetime.
     *
     * <p>The interval is deliberately much longer than the lifetime. Sweeping at the lifetime would
     * be a query every minute to delete the handful of rows a sign-in leaves behind, and there is
     * nothing to gain from a row disappearing promptly — it stopped being usable the moment it
     * expired.
     */
    @Scheduled(fixedDelayString = "300000", scheduler = SchedulingConfig.MAINTENANCE)
    void sweepExpiredCodes() {
        try {
            codes.pruneExpired();
        } catch (RuntimeException e) {
            // A sweep that fails changes no decision — see the class javadoc — so it must not take
            // the maintenance pool's other jobs down with it.
            log.warn("Expired login codes could not be pruned", e);
        }
    }
}
