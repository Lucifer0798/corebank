package com.corebank.account.service;

import com.corebank.account.repository.AccountRepository;
import com.corebank.customer.service.CustomerClosureCheck;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A closed customer holding an open account could never use it: nothing can leave it, and no one is
 * left to answer for it. Frozen accounts count -- a freeze is not a closure -- and the numbers are
 * named so staff can clear them all in one pass.
 */
@Component
class OpenAccountsClosureCheck implements CustomerClosureCheck {

    private final AccountRepository accounts;

    OpenAccountsClosureCheck(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public Optional<String> outstandingFor(UUID customerId) {
        List<String> open = accounts.findOpenAccountNumbers(customerId);
        return open.isEmpty()
                ? Optional.empty()
                : Optional.of(open.size() + (open.size() == 1 ? " open account (" : " open accounts (")
                        + String.join(", ", open) + ")");
    }
}
