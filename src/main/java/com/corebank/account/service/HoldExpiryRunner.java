package com.corebank.account.service;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Releases holds nobody captured in time.
 *
 * <p>The sweep is housekeeping, not a correctness guarantee: {@code AccountHold.assertCapturable}
 * already refuses a capture past the expiry instant, so a hold that is over stops being claimable
 * the moment it expires rather than the moment this runs. What the sweep does is give the
 * customer their available balance back, which is why it can afford to run on a slow interval and
 * to skip anything another transaction is holding.
 *
 * <p>Each hold is expired in its own transaction so one failure cannot take the batch with it --
 * the same shape {@code ScheduledTransferRunner} uses.
 */
@Component
@ConditionalOnProperty(prefix = "corebank.holds", name = "sweep-enabled",
        havingValue = "true", matchIfMissing = true)
public class HoldExpiryRunner {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryRunner.class);

    private final HoldService holdService;

    public HoldExpiryRunner(HoldService holdService) {
        this.holdService = holdService;
    }

    @Scheduled(fixedDelayString = "${corebank.holds.sweep-interval:5m}")
    public void sweep() {
        for (UUID id : holdService.findExpired()) {
            try {
                holdService.expire(id);
            } catch (RuntimeException ex) {
                // One stuck hold must not stop the others being freed; the next tick tries again.
                log.warn("Could not expire hold {}: {}", id, ex.toString());
            }
        }
    }
}
