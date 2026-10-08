package com.corebank.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebank.account.domain.AccountType;
import com.corebank.account.dto.OpenAccountRequest;
import com.corebank.customer.domain.KycStatus;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.service.CustomerService;
import com.corebank.transaction.dto.AmountRequest;
import com.corebank.transaction.service.TransactionService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Interest against a real ledger, with the rounding rules as the subject.
 *
 * <p>Everything here is ultimately one question: does a year of daily accrual pay the customer
 * what the annual rate says it should? Interest bugs do not announce themselves -- they are a
 * fraction of a paisa a day in one party's favour, invisible on any single account and material
 * across a portfolio -- so the tests that matter are the ones about accumulation and rounding
 * rather than about any single posting being correct.
 */
@SpringBootTest
class InterestServiceTest {

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    @Autowired
    private InterestService interestService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private com.corebank.account.repository.AccountRepository accounts;

    private UUID accountId;

    @BeforeEach
    void setUp() {
        int n = UNIQUE.incrementAndGet();
        UUID customerId = customerService.create(new CreateCustomerRequest(
                "Devika", "Nair", "devika.nair." + n + "@example.com", null,
                LocalDate.of(1990, 2, 14))).id();
        customerService.updateKyc(customerId, KycStatus.VERIFIED, "Test fixture", com.corebank.config.TestDeciders.STAFF);

        accountId = accountService.open(new OpenAccountRequest(
                customerId, AccountType.SAVINGS, "INR", BigDecimal.ZERO)).id();
        transactionService.deposit(accountId,
                new AmountRequest(new BigDecimal("10000.00"), "INR", "Funding"), "int-fund-" + n);
    }

    private BigDecimal accrued() {
        return accountService.require(accountId).getAccruedInterest();
    }

    /**
     * Read without a lock. {@code AccountService.requireInternalAccount} takes a pessimistic one
     * and is not itself transactional, so calling it from the test thread has no transaction to
     * belong to -- the same trap HoldServiceTest hit with a hand-built service.
     */
    private BigDecimal interestExpenseBalance() {
        return accounts.findByAccountNumber("GL0000000003").orElseThrow().getBalance();
    }

    @Test
    @DisplayName("a day's interest is kept at four decimal places, not rounded to paisa")
    void dailyAccrualKeepsTheFraction() {
        // 10000 at 3.5%/365 is 0.9589 a day. Rounded to two places nightly it would be 0.96 --
        // over-paying by 4% -- and on a smaller balance it would round to 0.00 and the customer
        // would earn nothing at all.
        BigDecimal daily = interestService.dailyInterestOn(new BigDecimal("10000.00"));

        assertThat(daily).isEqualByComparingTo("0.9589");
        assertThat(daily.scale()).isEqualTo(4);
    }

    @Test
    @DisplayName("a small balance still earns something")
    void aSmallBalanceStillEarns() {
        // 100 at 3.5%/365 is under a paisa a day. At two decimal places this is zero forever,
        // which is the failure mode the storage scale exists to prevent.
        BigDecimal daily = interestService.dailyInterestOn(new BigDecimal("100.00"));

        assertThat(daily).isGreaterThan(BigDecimal.ZERO);
        assertThat(daily).isLessThan(new BigDecimal("0.01"));
    }

    @Test
    @DisplayName("an empty or overdrawn balance earns nothing rather than owing")
    void nonPositiveBalancesEarnNothing() {
        // Inferring an overdraft charge from a negative balance would make overdrafts silently
        // start costing money, which is a different product with different disclosure rules.
        assertThat(interestService.dailyInterestOn(BigDecimal.ZERO)).isEqualByComparingTo("0.00");
        assertThat(interestService.dailyInterestOn(new BigDecimal("-500.00"))).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("accruing the same day twice adds nothing the second time")
    void accrualIsIdempotentPerDay() {
        LocalDate day = LocalDate.of(2026, 6, 1);

        assertThat(interestService.accrue(accountId, day)).isTrue();
        BigDecimal afterFirst = accrued();

        assertThat(interestService.accrue(accountId, day))
                .describedAs("a rerun, or a second replica, must not pay the day twice")
                .isFalse();
        assertThat(accrued()).isEqualByComparingTo(afterFirst);
    }

    @Test
    @DisplayName("capitalising pays whole paisa and carries the remainder")
    void capitalisationCarriesTheRemainder() {
        // Three days at 0.9589 is 2.8767. Paying 2.88 would invent 0.0033 of the bank's money;
        // paying 2.87 and forgetting the rest would quietly keep 0.0067 of the customer's.
        for (int i = 0; i < 3; i++) {
            interestService.accrue(accountId, LocalDate.of(2026, 6, 1).plusDays(i));
        }
        assertThat(accrued()).isEqualByComparingTo("2.8767");

        String reference = interestService.capitalise(accountId).orElseThrow();

        assertThat(accountService.get(accountId).balance()).isEqualByComparingTo("10002.87");
        assertThat(accrued())
                .describedAs("the sub-paisa remainder is carried, not discarded")
                .isEqualByComparingTo("0.0067");
        assertThat(reference).startsWith("TXN-");
    }

    @Test
    @DisplayName("capitalisation posts a balanced transaction against the interest expense account")
    void capitalisationIsABalancedPosting() {
        interestService.accrue(accountId, LocalDate.of(2026, 6, 1));
        BigDecimal expenseBefore = interestExpenseBalance();

        String reference = interestService.capitalise(accountId).orElseThrow();

        var posting = transactionService.getByReference(reference);
        assertThat(posting.type().name()).isEqualTo("INTEREST");
        assertThat(posting.legs()).hasSize(2);
        assertThat(posting.legs().get(0).accountNumber()).isEqualTo("GL0000000003");
        assertThat(posting.legs().get(0).direction().name()).isEqualTo("DEBIT");
        assertThat(posting.legs().get(1).direction().name()).isEqualTo("CREDIT");

        // The bank's expense grew by exactly what the customer was paid -- the money came from
        // somewhere, which is what makes this a posting rather than an invention.
        BigDecimal expenseAfter = interestExpenseBalance();
        assertThat(expenseAfter.subtract(expenseBefore)).isEqualByComparingTo(posting.amount());
    }

    @Test
    @DisplayName("nothing whole to pay means no posting at all")
    void nothingToCapitalise() {
        // A zero-amount transaction would be refused by the ledger's own amount constraint, so
        // this has to be decided before a posting is built rather than caught by it.
        assertThat(interestService.capitalise(accountId)).isEmpty();
    }

    @Test
    @DisplayName("a year of daily accrual pays what the annual rate promised")
    void aYearOfAccrualMatchesTheAnnualRate() {
        // The assertion the whole feature answers to, and the one that catches a systematic
        // rounding error in either direction -- the kind that is invisible on a single account and
        // material across a portfolio.
        //
        // Bounded rather than pinned to a figure, because both bounds come from arithmetic rather
        // than from this implementation:
        //
        //   * strictly MORE than simple interest (10000 * 3.5% = 350.00), because capitalising
        //     adds the interest to the balance and later days then earn on it. Coming in at or
        //     below 350 would mean capitalisation is not compounding, or interest is being lost;
        //   * strictly LESS than continuous compounding (10000 * (e^0.035 - 1) = 356.1971), which
        //     is the mathematical ceiling for this nominal rate at any compounding frequency.
        //     Exceeding it would mean a day is being counted twice somewhere.
        //
        // Daily accrual capitalised monthly lands between the two, near the top of the range.
        LocalDate day = LocalDate.of(2026, 1, 1);
        BigDecimal paid = BigDecimal.ZERO;

        for (int i = 0; i < 365; i++) {
            LocalDate on = day.plusDays(i);
            interestService.accrue(accountId, on);
            if (on.getDayOfMonth() == 1 && i > 0) {
                BigDecimal before = accountService.get(accountId).balance();
                interestService.capitalise(accountId);
                paid = paid.add(accountService.get(accountId).balance().subtract(before));
            }
        }
        // Whatever is still accrued at the end is money earned and not yet paid; it counts.
        BigDecimal earned = paid.add(accrued());

        assertThat(earned)
                .describedAs("compounding must beat simple interest")
                .isGreaterThan(new BigDecimal("350.00"));
        assertThat(earned)
                .describedAs("but cannot beat continuous compounding at the same nominal rate")
                .isLessThan(new BigDecimal("356.1971"));
    }

    @Test
    @DisplayName("the accrual sweep picks up a brand new account")
    void theSweepFindsNewAccounts() {
        // interest_accrued_through is null on a fresh account, so a query written as
        // "accrued_through < today" alone would never see one and it would earn nothing, ever.
        LocalDate today = interestService.today();

        assertThat(interestService.findAccountsToAccrue(today)).contains(accountId);

        interestService.accrue(accountId, today);
        assertThat(interestService.findAccountsToAccrue(today)).doesNotContain(accountId);
    }
}
