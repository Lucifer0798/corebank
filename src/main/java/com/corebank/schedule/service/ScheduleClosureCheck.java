package com.corebank.schedule.service;

import com.corebank.account.service.AccountClosureCheck;
import com.corebank.schedule.domain.ScheduleStatus;
import com.corebank.schedule.repository.ScheduledTransferRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A standing instruction keeps firing at an account whatever its status, so closing under a live
 * one means a refusal -- and a "could not be processed" notification to the payer -- on every
 * occurrence until it suspends.
 *
 * <p>Both directions count. One paying <em>into</em> the account may belong to a different
 * customer, which is exactly why closure refuses rather than cancelling them itself: quietly
 * stopping someone else's instruction is worse than asking staff to deal with it on purpose.
 *
 * <p>SUSPENDED counts too, since staff can resume one. Cancel it to retire it.
 */
@Component
class ScheduleClosureCheck implements AccountClosureCheck {

    private static final List<ScheduleStatus> OUTSTANDING = List.of(ScheduleStatus.ACTIVE, ScheduleStatus.SUSPENDED);

    private final ScheduledTransferRepository schedules;

    ScheduleClosureCheck(ScheduledTransferRepository schedules) {
        this.schedules = schedules;
    }

    @Override
    public Optional<String> outstandingFor(UUID accountId) {
        long outstanding = schedules.countForAccountWithStatusIn(accountId, OUTSTANDING);
        return outstanding == 0
                ? Optional.empty()
                : Optional.of(outstanding + (outstanding == 1 ? " standing instruction" : " standing instructions"));
    }
}
