package com.corebank.account.service;

import com.corebank.account.domain.Account;
import com.corebank.account.repository.AccountRepository;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ownership checks referenced from {@code @PreAuthorize}. Staff see every account;
 * a self-service login sees only the accounts of the customer its token names.
 */
@Component("accountSecurity")
public class AccountSecurity {

    private final AccountRepository accounts;

    public AccountSecurity(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public boolean canReadAccount(Authentication authentication, UUID accountId) {
        if (isStaff(authentication)) {
            return true;
        }
        UUID customerId = customerId(authentication);
        if (customerId == null) {
            return false;
        }
        return accounts.findById(accountId)
                .map(Account::getCustomer)
                .map(customer -> customer.getId().equals(customerId))
                .orElse(false);
    }

    public boolean canReadCustomer(Authentication authentication, UUID customerId) {
        return isStaff(authentication) || customerId.equals(customerId(authentication));
    }

    private boolean isStaff(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_TELLER".equals(authority.getAuthority())
                        || "ROLE_ADMIN".equals(authority.getAuthority()));
    }

    private UUID customerId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return null;
        }
        String claim = jwt.getClaimAsString("customerId");
        return claim == null ? null : UUID.fromString(claim);
    }
}
