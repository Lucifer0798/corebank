package com.corebank.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the ledger check every night, so a problem surfaces without anyone having to think to look.
 * At ERROR, since a ledger that does not add up is exactly what that level is for, and through
 * {@value LedgerCheckService#METRIC} for anything watching Prometheus.
 *
 * <p>Nightly rather than often: these are totals over whole tables, and an admin can run the same
 * check at any moment from the Admin page.
 *
 * <p>Switched off in the plain test suite, where tests corrupt rows on purpose.
 */
@Component
@ConditionalOnProperty(prefix = "corebank.ledger-check", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class LedgerCheckRunner {

    private static final Logger log = LoggerFactory.getLogger(LedgerCheckRunner.class);

    private final LedgerCheckService service;

    public LedgerCheckRunner(LedgerCheckService service) {
        this.service = service;
    }

    @Scheduled(cron = "${corebank.ledger-check.cron:0 30 2 * * *}", zone = "UTC")
    public void run() {
        LedgerCheckReport report = service.check();
        if (report.clean()) {
            log.info("Ledger check found nothing wrong");
            return;
        }
        for (LedgerCheckReport.Result result : report.checks()) {
            for (LedgerCheckReport.Discrepancy discrepancy : result.discrepancies()) {
                log.error("Ledger check {} failed for {}: {}", result.check(), discrepancy.subject(), discrepancy.detail());
            }
            if (result.more()) {
                log.error("Ledger check {} found more than the {} listed", result.check(), result.discrepancies().size());
            }
        }
    }
}
