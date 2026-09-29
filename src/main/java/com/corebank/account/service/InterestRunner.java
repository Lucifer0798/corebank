package com.corebank.account.service;

import java.time.LocalDate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Adds a day's interest to every savings account, and on the first of the month turns what has
 * accumulated into a posting.
 *
 * <p>Ticking more often than once a day is intentional and harmless: {@code Account} refuses a day
 * it has already been accrued for, so the extra ticks are how the run catches accounts opened since
 * the last one and how it finishes a batch too large for a single pass. It is also what makes a
 * restart mid-run safe, since nothing depends on the process surviving to the end.
 *
 * <p>Disabled in the test suite, like the other two runners -- a background job that credits
 * interest underneath unrelated tests would make their balances depend on the wall clock.
 */
@Component
@ConditionalOnProperty(prefix = "corebank.interest", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class InterestRunner {

    private static final Logger log = LoggerFactory.getLogger(InterestRunner.class);

    private final InterestService interestService;

    public InterestRunner(InterestService interestService) {
        this.interestService = interestService;
    }

    @Scheduled(fixedDelayString = "${corebank.interest.accrual-interval:1h}")
    public void run() {
        LocalDate today = interestService.today();

        for (UUID id : interestService.findAccountsToAccrue(today)) {
            try {
                interestService.accrue(id, today);
            } catch (RuntimeException ex) {
                // One account's failure must not cost every other account its day's interest.
                log.warn("Could not accrue interest on account {}: {}", id, ex.toString());
            }
        }

        if (interestService.isCapitalisationDay()) {
            for (UUID id : interestService.findAccountsToCapitalise()) {
                try {
                    interestService.capitalise(id);
                } catch (RuntimeException ex) {
                    log.warn("Could not capitalise interest on account {}: {}", id, ex.toString());
                }
            }
        }
    }
}
