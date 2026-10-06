package com.corebank.account.service;

import com.corebank.account.domain.HoldStatus;
import com.corebank.account.repository.AccountHoldRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * An outstanding authorisation is a guarantee to a merchant that the money will be there when they
 * capture it. Closing the account under it would turn that capture into a refusal.
 */
@Component
class HoldClosureCheck implements AccountClosureCheck {

    private final AccountHoldRepository holds;

    HoldClosureCheck(AccountHoldRepository holds) {
        this.holds = holds;
    }

    @Override
    public Optional<String> outstandingFor(UUID accountId) {
        long active = holds.countByAccount_IdAndStatus(accountId, HoldStatus.ACTIVE);
        return active == 0
                ? Optional.empty()
                : Optional.of(active + (active == 1 ? " outstanding hold" : " outstanding holds"));
    }
}
