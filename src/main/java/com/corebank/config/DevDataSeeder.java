package com.corebank.config;

import com.corebank.account.domain.AccountType;
import com.corebank.account.dto.AccountResponse;
import com.corebank.account.dto.OpenAccountRequest;
import com.corebank.account.service.AccountService;
import com.corebank.customer.domain.KycStatus;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.dto.CustomerResponse;
import com.corebank.customer.repository.CustomerRepository;
import com.corebank.customer.service.CustomerService;
import com.corebank.transaction.dto.AmountRequest;
import com.corebank.transaction.service.TransactionService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Fills the development database with a walkable example: a verified customer, one funded
 * savings account and one current account. Runs only under the {@code dev} profile, and goes
 * through the ordinary services so the seeded data obeys the same rules as anything created
 * through the API.
 *
 * <p>The seeded customer is linked to the fixed {@code sub} that {@code keycloak/corebank-realm.json}
 * assigns to the demo user "asha" -- so if Keycloak is also running (it must be, for any
 * authenticated request to work at all), logging in as asha / Customer#2025 shows exactly this
 * customer's accounts.
 */
@Profile("dev")
@Configuration
public class DevDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    /** Matches the "asha" user's fixed id in keycloak/corebank-realm.json. */
    static final String ASHA_KEYCLOAK_SUBJECT = "00000000-0000-4000-8000-000000000003";
    private static final String ASHA_EMAIL = "asha.menon@example.com";

    @Bean
    public ApplicationRunner seedDevelopmentData(CustomerRepository customers,
                                                 CustomerService customerService,
                                                 AccountService accountService,
                                                 TransactionService transactionService) {
        return args -> {
            if (customers.existsByEmailIgnoreCase(ASHA_EMAIL)) {
                return;
            }

            CustomerResponse customer = customerService.create(new CreateCustomerRequest(
                    "Asha", "Menon", ASHA_EMAIL, "+919876543210", LocalDate.of(1995, 4, 17)));
            customerService.updateKyc(customer.id(), KycStatus.VERIFIED);
            customerService.linkIdentity(customer.id(), ASHA_KEYCLOAK_SUBJECT);

            AccountResponse savings = accountService.open(new OpenAccountRequest(
                    customer.id(), AccountType.SAVINGS, "INR", BigDecimal.ZERO));
            AccountResponse current = accountService.open(new OpenAccountRequest(
                    customer.id(), AccountType.CURRENT, "INR", new BigDecimal("5000.00")));

            transactionService.deposit(savings.id(),
                    new AmountRequest(new BigDecimal("25000.00"), "INR", "Opening deposit"),
                    "seed-" + UUID.randomUUID());

            log.info("""
                    Development data ready. Authenticated requests need Keycloak running
                    (docker compose up -d keycloak) regardless of this profile:
                      admin / ChangeMe#2025!   (ADMIN)
                      teller1 / Teller#2025    (TELLER)
                      asha / Customer#2025     (CUSTOMER, linked to the customer seeded below)
                      savings account {} funded with 25000.00
                      current account {} with a 5000.00 overdraft
                    """, savings.accountNumber(), current.accountNumber());
        };
    }
}
