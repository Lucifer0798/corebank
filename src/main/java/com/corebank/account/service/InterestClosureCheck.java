package com.corebank.account.service;

import com.corebank.account.domain.Account;
import com.corebank.account.repository.AccountRepository;
import com.corebank.common.Money;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Interest accrued but not yet paid out is money the bank owes the customer. An account emptied to
 * zero used to close with it still on the books, and the next monthly run then credited it into the
 * closed account, where nothing could ever withdraw it.
 *
 * <p>Whole paise only. The fraction of a paisa that capitalisation always carries forward cannot be
 * paid in real money, so it is no reason to keep an account open.
 */
@Component
class InterestClosureCheck implements AccountClosureCheck {

    private final AccountRepository accounts;

    InterestClosureCheck(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public Optional<String> outstandingFor(UUID accountId) {
        // The instance closure has already locked: this transaction's persistence context holds it.
        Account account = accounts.findById(accountId).orElseThrow();
        BigDecimal payable = account.capitalisableInterest();
        return Money.isPositive(payable)
                ? Optional.of(payable + " " + account.getCurrency() + " of interest not yet paid out")
                : Optional.empty();
    }
}
