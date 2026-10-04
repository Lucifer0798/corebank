package com.corebank.fx.service;

import com.corebank.account.domain.Account;
import com.corebank.account.domain.AccountType;
import com.corebank.account.domain.EntryDirection;
import com.corebank.account.repository.AccountRepository;
import com.corebank.account.service.AccountService;
import com.corebank.common.Money;
import com.corebank.fx.dto.FxPositionReport;
import com.corebank.transaction.domain.BankTransaction;
import com.corebank.transaction.domain.TransactionStatus;
import com.corebank.transaction.domain.TransactionType;
import com.corebank.transaction.service.TransactionService;
import com.corebank.transaction.service.ReferenceGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the bank's FX book is worth, and the posting that recognises a change in it.
 *
 * <p>V10 built the position accounts and nothing read them. A bank holding offsetting amounts in
 * four currencies and never valuing them does not know its own exposure -- the trading looks
 * correct account by account while the one number anybody would ask for does not exist.
 *
 * <p>Positions are valued at the <em>directly quoted</em> rate into the reporting currency
 * (USD/INR), never at the inverse of the opposite quote (1 / INR/USD). Those disagree in this
 * book by up to 0.64%, and the directly quoted one is the rate the bank could actually transact
 * at, so it is the only one that answers "what would this realise".
 */
@Service
public class FxPositionService {

    private static final Logger log = LoggerFactory.getLogger(FxPositionService.class);

    /** The currency the book is reported in. Positions in it need no conversion. */
    static final String REPORTING_CURRENCY = Money.BASE_CURRENCY;

    static final String REVALUATION_ACCOUNT = "GL0000000014";
    static final String GAIN_LOSS_ACCOUNT = "GL0000000015";

    private final AccountRepository accounts;
    private final AccountService accountService;
    private final TransactionService transactionService;
    private final FxRateService fxRateService;
    private final ReferenceGenerator referenceGenerator;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public FxPositionService(AccountRepository accounts,
                             AccountService accountService,
                             TransactionService transactionService,
                             FxRateService fxRateService,
                             ReferenceGenerator referenceGenerator,
                             MeterRegistry meterRegistry,
                             Clock clock) {
        this.accounts = accounts;
        this.accountService = accountService;
        this.transactionService = transactionService;
        this.fxRateService = fxRateService;
        this.referenceGenerator = referenceGenerator;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /** The book, currency by currency, valued into the reporting currency. */
    @Transactional(readOnly = true)
    public FxPositionReport report() {
        List<FxPositionReport.Line> lines = new ArrayList<>();
        BigDecimal net = BigDecimal.ZERO;

        for (Account position : positionAccounts()) {
            String currency = position.getCurrency();
            BigDecimal balance = Money.normalize(position.getBalance());
            BigDecimal rate = rateToReporting(currency);
            BigDecimal valued = valueIn(balance, rate);

            lines.add(new FxPositionReport.Line(currency, position.getAccountNumber(), balance, rate, valued));
            net = net.add(valued);
        }

        BigDecimal mark = Money.normalize(net);
        return new FxPositionReport(REPORTING_CURRENCY, lines, mark,
                Money.normalize(markCarried()), Instant.now(clock));
    }

    /**
     * Recognises the change in the mark since the last run.
     *
     * <p>Posts the <em>delta</em>, not the mark, and that is the whole of the design. The
     * revaluation account carries the mark as its balance, so a run only has to move it to where
     * the market now says it should be -- which means running twice in a row is harmless, and a
     * missed close is caught up by the next one rather than lost. Posting the mark itself would
     * double it on every run.
     *
     * <p>Both legs are in the reporting currency, necessarily: the per-currency balance rule means
     * a posting mixing INR with USD cannot balance. The foreign positions are untouched -- nothing
     * has happened to them, which is precisely what an <em>unrealised</em> gain means.
     */
    @Transactional
    public Optional<String> revalue() {
        BigDecimal target = report().markToMarket();
        BigDecimal carried = markCarried();
        BigDecimal delta = Money.normalize(target.subtract(carried));

        if (!Money.isPositive(delta.abs())) {
            // Nothing moved. A zero-amount posting is refused by the ledger anyway, and an empty
            // close is a normal outcome rather than an error.
            return Optional.empty();
        }

        Account reserve = accountService.requireInternalAccount(REVALUATION_ACCOUNT);
        Account gainLoss = accountService.requireInternalAccount(GAIN_LOSS_ACCOUNT);

        BankTransaction transaction = new BankTransaction();
        transaction.setReference(referenceGenerator.next("FXR"));
        transaction.setType(TransactionType.FX_REVALUATION);
        transaction.setStatus(TransactionStatus.POSTED);
        transaction.setAmount(delta.abs());
        transaction.setCurrency(REPORTING_CURRENCY);
        transaction.setDescription("FX revaluation to " + target + " " + REPORTING_CURRENCY);
        transaction.setPostedAt(Instant.now(clock));

        if (delta.compareTo(BigDecimal.ZERO) > 0) {
            // The book is worth more than it was carried at: the reserve rises, and the difference
            // is a gain.
            transaction.addEntry(reserve, EntryDirection.DEBIT, delta.abs());
            transaction.addEntry(gainLoss, EntryDirection.CREDIT, delta.abs());
        } else {
            transaction.addEntry(gainLoss, EntryDirection.DEBIT, delta.abs());
            transaction.addEntry(reserve, EntryDirection.CREDIT, delta.abs());
        }

        // Through the one road to the ledger. Saving directly used to skip the event, so a
        // revaluation never reached search or any downstream consumer, and skipped the metrics.
        String reference = transactionService.postSystemTransaction(transaction).reference();
        meterRegistry.counter("corebank.fx.revaluations").increment();
        log.info("Revalued the FX book to {} {} ({} {})",
                target, REPORTING_CURRENCY, delta.signum() > 0 ? "+" + delta : delta, REPORTING_CURRENCY);
        return Optional.of(reference);
    }

    /** What the reserve account currently says the book is worth. */
    @Transactional(readOnly = true)
    public BigDecimal markCarried() {
        return accounts.findByAccountNumber(REVALUATION_ACCOUNT)
                .map(Account::getBalance)
                .orElse(BigDecimal.ZERO);
    }

    private List<Account> positionAccounts() {
        return accounts.findAll().stream()
                .filter(account -> account.getAccountType() == AccountType.FX_POSITION_GL)
                .sorted(Comparator.comparing(Account::getCurrency))
                .toList();
    }

    /**
     * A unit of {@code currency} in reporting-currency terms. One, for the reporting currency
     * itself -- a rupee is a rupee, and asking the rate table for INR/INR would be a lookup with
     * no row and no meaning.
     */
    private BigDecimal rateToReporting(String currency) {
        return currency.equals(REPORTING_CURRENCY)
                ? BigDecimal.ONE
                : fxRateService.require(currency, REPORTING_CURRENCY).getMidRate();
    }

    /**
     * Valued at the <em>mid</em> rate, deliberately. The spread is what the bank would charge to
     * close the position out, and marking a book at your own spread reports a profit you have not
     * made yet.
     */
    private BigDecimal valueIn(BigDecimal balance, BigDecimal rate) {
        return balance.multiply(rate).setScale(Money.SCALE, RoundingMode.HALF_UP);
    }
}
