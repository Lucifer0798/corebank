package com.corebank.account.service;

import com.corebank.account.domain.Account;
import com.corebank.account.domain.AccountStatus;
import com.corebank.account.domain.AccountType;
import com.corebank.account.domain.EntryDirection;
import com.corebank.account.repository.AccountRepository;
import com.corebank.common.Money;
import com.corebank.config.CoreBankProperties;
import com.corebank.transaction.domain.BankTransaction;
import com.corebank.transaction.domain.TransactionStatus;
import com.corebank.transaction.domain.TransactionType;
import com.corebank.transaction.repository.BankTransactionRepository;
import com.corebank.transaction.service.ReferenceGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Interest on savings: accrued daily at full scale, capitalised monthly as a real posting.
 *
 * <p>Accrual is not a posting and deliberately so. Nothing has moved -- the bank owes a little more
 * than it did yesterday, but no money has changed hands -- so writing a ledger entry every night
 * would fill the statement with 365 lines a year for something the customer cannot spend yet. The
 * amount accumulates on the account at {@code NUMERIC(19,4)} and becomes a single posting when it
 * is capitalised.
 *
 * <p>The two halves round differently, and the difference is the whole correctness argument.
 * Accrual keeps four decimal places, because a day's interest on an ordinary balance is a fraction
 * of a paisa and rounding it nightly would pay the customer nothing at all. Capitalisation rounds
 * down to two and carries the remainder, because paying out a rounded-up fraction is the bank
 * inventing money and discarding it is the bank keeping the customer's.
 */
@Service
public class InterestService {

    private static final Logger log = LoggerFactory.getLogger(InterestService.class);

    /** One accrual tick. Bounded so a large portfolio is worked through over several ticks. */
    static final int BATCH_SIZE = 200;

    private final AccountRepository accounts;
    private final BankTransactionRepository transactions;
    private final AccountService accountService;
    private final ReferenceGenerator referenceGenerator;
    private final CoreBankProperties.Interest properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public InterestService(AccountRepository accounts,
                           BankTransactionRepository transactions,
                           AccountService accountService,
                           ReferenceGenerator referenceGenerator,
                           CoreBankProperties properties,
                           MeterRegistry meterRegistry,
                           Clock clock) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.accountService = accountService;
        this.referenceGenerator = referenceGenerator;
        this.properties = properties.interest();
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /**
     * One day's interest on a balance, at full scale.
     *
     * <p>Scale 10 for the division, not the storage scale: the daily rate itself is a tiny
     * recurring fraction, and truncating it before multiplying would lose far more than the final
     * rounding does. Only the product comes back to four places.
     */
    public BigDecimal dailyInterestOn(BigDecimal balance) {
        if (balance.compareTo(BigDecimal.ZERO) <= 0) {
            // An overdrawn or empty account earns nothing. Charging interest on a negative balance
            // is a different product with different rules, and inferring it from a sign would make
            // an overdraft silently start costing money.
            return BigDecimal.ZERO.setScale(4);
        }
        BigDecimal dailyRate = properties.savingsAnnualRate()
                .divide(BigDecimal.valueOf(properties.dayCountBasis()), 10, RoundingMode.HALF_UP);
        return balance.multiply(dailyRate).setScale(4, RoundingMode.HALF_UP);
    }

    @Transactional(readOnly = true)
    public List<UUID> findAccountsToAccrue(LocalDate day) {
        return accounts.findSavingsNotAccruedThrough(day, PageRequest.of(0, BATCH_SIZE));
    }

    /** Adds one day's interest to one account. Safe to repeat: the account refuses a repeat day. */
    @Transactional
    public boolean accrue(UUID accountId, LocalDate day) {
        Account account = accountService.requireForUpdate(accountId);
        if (account.getStatus() != AccountStatus.ACTIVE) {
            return false;
        }
        BigDecimal amount = dailyInterestOn(account.getBalance());
        boolean accrued = account.accrueInterestFor(day, amount);
        if (accrued) {
            meterRegistry.counter("corebank.interest.accrued").increment();
        }
        return accrued;
    }

    /**
     * Turns accrued interest into money the customer can spend.
     *
     * <p>A balanced posting like any other: the customer is credited and the bank's interest
     * expense account is debited. Returns empty when there is nothing whole to pay -- a brand new
     * account, or one whose accrual so far has not reached a paisa -- rather than writing a
     * zero-amount transaction the ledger would refuse anyway.
     */
    @Transactional
    public java.util.Optional<String> capitalise(UUID accountId) {
        Account account = accountService.requireForUpdate(accountId);
        BigDecimal payable = account.capitalisableInterest();
        if (!Money.isPositive(payable)) {
            return java.util.Optional.empty();
        }
        account.takeCapitalisableInterest();

        Account expense = accountService.requireInternalAccount("GL0000000003");
        BankTransaction transaction = new BankTransaction();
        transaction.setReference(referenceGenerator.next());
        transaction.setType(TransactionType.INTEREST);
        transaction.setStatus(TransactionStatus.POSTED);
        transaction.setAmount(payable);
        transaction.setCurrency(account.getCurrency());
        transaction.setDescription("Interest capitalised");
        transaction.setPostedAt(Instant.now(clock));
        // Expense first, mirroring how a deposit debits cash before crediting the customer.
        transaction.addEntry(expense, EntryDirection.DEBIT, payable);
        transaction.addEntry(account, EntryDirection.CREDIT, payable);
        transaction.assertBalanced();

        BankTransaction saved = transactions.save(transaction);
        accountService.evictCache(accountId);
        meterRegistry.counter("corebank.interest.capitalised").increment();
        log.info("Capitalised {} of interest on account {}", payable, accountId);
        return java.util.Optional.of(saved.getReference());
    }

    /** Savings accounts with something whole to pay. Drives the monthly capitalisation run. */
    @Transactional(readOnly = true)
    public List<UUID> findAccountsToCapitalise() {
        return accounts.findSavingsWithAccruedInterest(PageRequest.of(0, BATCH_SIZE));
    }

    /** Today, as the accrual run reckons it. */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    /** Whether today is a capitalisation day -- the first of the month. */
    public boolean isCapitalisationDay() {
        return today().getDayOfMonth() == 1;
    }

    static AccountType interestBearingType() {
        return AccountType.SAVINGS;
    }
}
