package dev.collabedit.app.auth;

import dev.collabedit.app.config.SchedulingConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LoginCodeSweep {
    private static final Logger log = LoggerFactory.getLogger(LoginCodeSweep.class);

    private final LoginCodeStore codes;

    public LoginCodeSweep(LoginCodeStore codes) {
        this.codes = codes;
    }

    @Scheduled(fixedDelayString = "300000", scheduler = SchedulingConfig.MAINTENANCE)
    void sweepExpiredCodes() {
        try {
            codes.pruneExpired();
        } catch (RuntimeException e) {
            log.warn("Expired login codes could not be pruned", e);
        }
    }
}
