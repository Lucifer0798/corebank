package com.corebank.account.service;

import com.corebank.account.domain.Account;
import com.corebank.account.repository.AccountRepository;
import com.corebank.customer.repository.CustomerRepository;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ownership checks referenced from {@code @PreAuthorize}. Staff see every account; a
 * CUSTOMER-role token sees only the accounts of whichever customer record has been linked to
 * its subject.
 *
 * <p>Ownership is resolved by looking up {@code customer.keycloak_subject} against the token's
 * {@code sub} claim, rather than trusting a customer id embedded in the token itself. {@code sub}
 * is the one claim Keycloak always issues and never lets drift out of sync with an
 * application-managed attribute, so it is the stable side of the relationship to key off.
 */
@Component("accountSecurity")
public class AccountSecurity {

    private final AccountRepository accounts;
    private final CustomerRepository customers;

    public AccountSecurity(AccountRepository accounts, CustomerRepository customers) {
        this.accounts = accounts;
        this.customers = customers;
    }

    @Transactional(readOnly = true)
    public boolean canReadAccount(Authentication authentication, UUID accountId) {
        if (isStaff(authentication)) {
            return true;
        }
        String subject = subject(authentication);
        if (subject == null) {
            return false;
        }
        return accounts.findById(accountId)
                .map(Account::getCustomer)
                .map(customer -> subject.equals(customer.getKeycloakSubject()))
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean canReadCustomer(Authentication authentication, UUID customerId) {
        if (isStaff(authentication)) {
            return true;
        }
        String subject = subject(authentication);
        if (subject == null) {
            return false;
        }
        return customers.findById(customerId)
                .map(customer -> subject.equals(customer.getKeycloakSubject()))
                .orElse(false);
    }

    private boolean isStaff(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_TELLER".equals(authority.getAuthority())
                        || "ROLE_ADMIN".equals(authority.getAuthority()));
    }

    private String subject(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return null;
        }
        return jwt.getSubject();
    }
}
