package com.corebank.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.corebank.account.domain.Account;
import com.corebank.account.domain.AccountType;
import com.corebank.account.dto.OpenAccountRequest;
import com.corebank.account.repository.AccountRepository;
import com.corebank.common.security.Actors;
import com.corebank.config.TestDeciders;
import com.corebank.customer.domain.KycStatus;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.service.CustomerService;
import com.corebank.transaction.dto.AmountRequest;
import com.corebank.transaction.service.TransactionService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Every account in one tick, however many there are.
 *
 * <p>A tick used to fetch the first batch of 200, work through it and stop, so accrual reached at
 * most 200 accounts an hour -- 4,800 a day -- and began again from the lowest id the next day. Past
 * that, the same accounts were missed every day and never earned at all. Capitalisation had the
 * same ceiling, and worse: an account that failed every time kept its interest, so it stayed in the
 * first batch and took a place in it on every tick.
 */
class InterestRunnerBatchTest {

    /** More than one batch, so the second one has to be fetched. */
    private static final int MORE_THAN_A_BATCH = InterestService.BATCH_SIZE + 5;

    @Nested
    @DisplayName("against the real ledger")
    @SpringBootTest
    class AgainstTheLedger {

        @Autowired private InterestService interestService;
        @Autowired private AccountService accountService;
        @Autowired private CustomerService customerService;
        @Autowired private TransactionService transactionService;
        @Autowired private AccountRepository accounts;
        @Autowired private JdbcTemplate jdbc;

        /** Savings accounts across enough customers to stay under the per-customer limit. */
        private List<UUID> savingsAccounts(int count, String deposit) {
            List<UUID> ids = new ArrayList<>();
            UUID customerId = null;
            for (int i = 0; i < count; i++) {
                if (i % 5 == 0) {
                    customerId = customerService.create(new CreateCustomerRequest("Batch", "Holder",
                            "batch." + UUID.randomUUID() + "@example.com", null, LocalDate.of(1990, 1, 1))).id();
                    customerService.updateKyc(customerId, KycStatus.VERIFIED, null, TestDeciders.STAFF);
                }
                UUID id = accountService.open(new OpenAccountRequest(customerId, AccountType.SAVINGS, "INR", BigDecimal.ZERO)).id();
                if (deposit != null) {
                    transactionService.deposit(id, new AmountRequest(new BigDecimal(deposit), "INR", "Funding"),
                            "batch-" + UUID.randomUUID());
                }
                ids.add(id);
            }
            return ids;
        }

        @Test
        @DisplayName("one tick accrues every account, not just the first batch")
        void oneTickAccruesEveryAccount() {
            List<UUID> ids = savingsAccounts(MORE_THAN_A_BATCH, null);
            LocalDate today = interestService.today();

            new InterestRunner(interestService).accrueAll(today);

            assertThat(accounts.findAllById(ids)).extracting(Account::getInterestAccruedThrough)
                    .hasSize(MORE_THAN_A_BATCH)
                    .containsOnly(today);
        }

        @Test
        @DisplayName("one capitalisation run pays every account, not just the first batch")
        void oneRunCapitalisesEveryAccount() {
            List<UUID> ids = savingsAccounts(MORE_THAN_A_BATCH, "10000.00");
            for (UUID id : ids) {
                interestService.accrue(id, LocalDate.of(2026, 1, 1));
            }
            // One account whose capitalisation fails every time: there is no interest-expense
            // account in a currency the bank does not hold, so it keeps its interest and is still
            // due on every fetch. A run that fetches the first page again instead of moving past it
            // would never finish.
            UUID failing = ids.removeFirst();
            jdbc.update("update account set currency = 'XYZ' where id = ?", failing);

            InterestRunner runner = new InterestRunner(interestService);
            assertTimeoutPreemptively(Duration.ofSeconds(120),
                    () -> Actors.runAs(InterestRunner.ACTOR, runner::capitaliseAll));

            // 0.9589 accrued: 0.95 paid, 0.0089 carried.
            assertThat(accounts.findAllById(ids)).extracting(Account::getBalance)
                    .hasSize(MORE_THAN_A_BATCH - 1)
                    .allSatisfy(balance -> assertThat(balance).isEqualByComparingTo("10000.95"));
            assertThat(accounts.findById(failing).orElseThrow().getAccruedInterest()).isEqualByComparingTo("0.9589");
        }
    }

    @Nested
    @DisplayName("when accounts fail")
    class WhenAccountsFail {

        private final LocalDate today = LocalDate.of(2026, 11, 2);

        /**
         * The first batch's worth of accounts fail every time and the rest succeed. Each fetch is a
         * page of accounts still to do after {@code after}, in a fixed order -- what the real
         * queries return.
         */
        private final List<UUID> failing = IntStream.range(0, InterestService.BATCH_SIZE)
                .mapToObj(i -> UUID.randomUUID()).toList();
        private final List<UUID> healthy = IntStream.range(0, 50).mapToObj(i -> UUID.randomUUID()).toList();
        private final List<UUID> all = new ArrayList<>(failing);
        private final Set<UUID> done = ConcurrentHashMap.newKeySet();

        WhenAccountsFail() {
            all.addAll(healthy);
        }

        private List<UUID> pageAfter(UUID after) {
            int from = after.equals(InterestService.FIRST) ? 0 : all.indexOf(after) + 1;
            return all.subList(from, all.size()).stream()
                    .filter(id -> !done.contains(id))
                    .limit(InterestService.BATCH_SIZE)
                    .toList();
        }

        private void work(UUID id) {
            if (failing.contains(id)) {
                throw new IllegalStateException("account " + id + " always fails");
            }
            done.add(id);
        }

        @Test
        @DisplayName("accounts that always fail do not stop accrual reaching the rest")
        void failuresDoNotBlockAccrual() {
            InterestService interest = mock(InterestService.class);
            when(interest.findAccountsToAccrue(eq(today), any())).thenAnswer(call -> pageAfter(call.getArgument(1)));
            when(interest.accrue(any(), eq(today))).thenAnswer(call -> {
                work(call.getArgument(0));
                return true;
            });

            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> new InterestRunner(interest).accrueAll(today));

            assertThat(done).containsExactlyInAnyOrderElementsOf(healthy);
        }

        @Test
        @DisplayName("accounts that always fail do not stop capitalisation reaching the rest")
        void failuresDoNotBlockCapitalisation() {
            InterestService interest = mock(InterestService.class);
            when(interest.findAccountsToCapitalise(any())).thenAnswer(call -> pageAfter(call.getArgument(0)));
            when(interest.capitalise(any())).thenAnswer(call -> {
                work(call.getArgument(0));
                return Optional.of("TXN-INTEREST");
            });

            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> new InterestRunner(interest).capitaliseAll());

            assertThat(done).containsExactlyInAnyOrderElementsOf(healthy);
        }
    }
}
