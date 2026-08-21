package com.corebank.config;

import com.corebank.account.domain.AccountType;
import com.corebank.account.dto.AccountResponse;
import com.corebank.account.dto.OpenAccountRequest;
import com.corebank.account.service.AccountService;
import com.corebank.auth.domain.Role;
import com.corebank.auth.dto.CreateUserRequest;
import com.corebank.auth.repository.AppUserRepository;
import com.corebank.auth.service.AuthService;
import com.corebank.customer.domain.KycStatus;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.dto.CustomerResponse;
import com.corebank.customer.service.CustomerService;
import com.corebank.transaction.dto.AmountRequest;
import com.corebank.transaction.service.TransactionService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;

/**
 * Fills the in-memory development database with a walkable example: a teller login, a
 * verified customer, one funded savings account and one current account. Runs only under the
 * {@code dev} profile, and goes through the ordinary services so the seeded data obeys the
 * same rules as anything created through the API.
 */
@Profile("dev")
@Configuration
public class DevDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    @Bean
    @Order(BootstrapAdminInitializer.BOOTSTRAP_ORDER + 1)
    public ApplicationRunner seedDevelopmentData(AppUserRepository users,
                                                 AuthService authService,
                                                 CustomerService customerService,
                                                 AccountService accountService,
                                                 TransactionService transactionService) {
        return args -> {
            if (users.existsByUsernameIgnoreCase("teller1")) {
                return;
            }

            authService.createUser(new CreateUserRequest(
                    "teller1", "Teller#2025", "Ravi Teller", Set.of(Role.TELLER), null));

            CustomerResponse customer = customerService.create(new CreateCustomerRequest(
                    "Asha", "Menon", "asha.menon@example.com", "+919876543210", LocalDate.of(1995, 4, 17)));
            customerService.updateKyc(customer.id(), KycStatus.VERIFIED);

            AccountResponse savings = accountService.open(new OpenAccountRequest(
                    customer.id(), AccountType.SAVINGS, "INR", BigDecimal.ZERO));
            AccountResponse current = accountService.open(new OpenAccountRequest(
                    customer.id(), AccountType.CURRENT, "INR", new BigDecimal("5000.00")));

            transactionService.deposit(savings.id(),
                    new AmountRequest(new BigDecimal("25000.00"), "INR", "Opening deposit"),
                    "seed-" + UUID.randomUUID());

            authService.createUser(new CreateUserRequest(
                    "asha", "Customer#2025", "Asha Menon", Set.of(Role.CUSTOMER), customer.id()));

            log.info("""
                    Development data ready.
                      admin / ChangeMe#2025!   (ADMIN)
                      teller1 / Teller#2025    (TELLER)
                      asha / Customer#2025     (CUSTOMER, sees only their own accounts)
                      savings account {} funded with 25000.00
                      current account {} with a 5000.00 overdraft
                    """, savings.accountNumber(), current.accountNumber());
        };
    }
}
