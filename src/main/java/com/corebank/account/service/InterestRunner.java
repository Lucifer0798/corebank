package com.corebank.account.service;

import com.corebank.common.metrics.BackgroundFailures;
import com.corebank.common.security.Actor;
import com.corebank.common.security.Actors;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Adds a day's interest to every savings account, and once a month -- from the 1st, until it is
 * done -- turns what has accumulated into a posting.
 *
 * <p>Ticking more often than once a day is intentional and harmless: {@code Account} refuses a day
 * it has already been accrued for, so the extra ticks are how the run catches accounts opened since
 * the last one. It is also what makes a restart mid-run safe, since nothing depends on the process
 * surviving to the end.
 *
 * <p>Each tick works through <em>every</em> account due, a batch at a time. It used to do one batch
 * and stop, trusting later ticks to finish -- which capped accrual at 200 accounts an hour, 4,800 a
 * day, starting from the lowest id each day, so beyond that the same accounts were missed every day
 * and never earned. Each batch starts after the last id of the one before, which is also what moves
 * the run past an account that fails every time instead of fetching it again.
 *
 * <p>Disabled in the test suite, like the other two runners -- a background job that credits
 * interest underneath unrelated tests would make their balances depend on the wall clock.
 */
@Component
@ConditionalOnProperty(prefix = "corebank.interest", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class InterestRunner {

    private static final Logger log = LoggerFactory.getLogger(InterestRunner.class);

    static final String ACCRUAL = "interest-accrual";
    static final String CAPITALISATION = "interest-capitalisation";

    private final InterestService interestService;
    private final Counter accrualFailures;
    private final Counter capitalisationFailures;

    public InterestRunner(InterestService interestService, MeterRegistry meterRegistry) {
        this.interestService = interestService;
        this.accrualFailures = BackgroundFailures.counter(meterRegistry, ACCRUAL);
        this.capitalisationFailures = BackgroundFailures.counter(meterRegistry, CAPITALISATION);
    }

    @Scheduled(fixedDelayString = "${corebank.interest.accrual-interval:1h}")
    public void run() {
        // Capitalisation posts, and no request stands behind it, so the runner says who it is.
        Actors.runAs(ACTOR, this::accrueAndCapitalise);
    }

    static final Actor ACTOR = Actor.system("interest-runner");

    private void accrueAndCapitalise() {
        LocalDate today = interestService.today();
        accrueAll(today);
        // Every tick, not only on the 1st. Only accounts not yet paid this month are due, so this
        // pays each one once a month -- on the 1st as before, or on the first tick after a 1st the
        // run never reached, rather than a month late.
        capitaliseAll(today);
    }

    void accrueAll(LocalDate today) {
        UUID after = InterestService.FIRST;
        for (List<UUID> batch = interestService.findAccountsToAccrue(today, after); !batch.isEmpty();
                batch = interestService.findAccountsToAccrue(today, after)) {
            for (UUID id : batch) {
                try {
                    interestService.accrue(id, today);
                } catch (RuntimeException ex) {
                    // One account's failure must not cost every other account its day's interest.
                    accrualFailures.increment();
                    log.warn("Could not accrue interest on account {}: {}", id, ex.toString());
                }
            }
            after = batch.getLast();
        }
    }

    void capitaliseAll(LocalDate today) {
        UUID after = InterestService.FIRST;
        for (List<UUID> batch = interestService.findAccountsToCapitalise(today, after); !batch.isEmpty();
                batch = interestService.findAccountsToCapitalise(today, after)) {
            for (UUID id : batch) {
                try {
                    interestService.capitalise(id, today);
                } catch (RuntimeException ex) {
                    capitalisationFailures.increment();
                    log.warn("Could not capitalise interest on account {}: {}", id, ex.toString());
                }
            }
            after = batch.getLast();
        }
    }
}
