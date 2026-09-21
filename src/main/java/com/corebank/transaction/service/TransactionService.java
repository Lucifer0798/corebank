package com.corebank.transaction.service;

import com.corebank.account.domain.Account;
import com.corebank.account.domain.EntryDirection;
import com.corebank.account.service.AccountService;
import com.corebank.common.Money;
import com.corebank.common.exception.BusinessRuleException;
import com.corebank.common.exception.ResourceNotFoundException;
import com.corebank.transaction.domain.BankTransaction;
import com.corebank.transaction.domain.TransactionStatus;
import com.corebank.transaction.domain.TransactionType;
import com.corebank.transaction.dto.AmountRequest;
import com.corebank.transaction.dto.ReversalRequest;
import com.corebank.transaction.dto.StatementLineResponse;
import com.corebank.transaction.dto.TransactionResponse;
import com.corebank.transaction.dto.TransferRequest;
import com.corebank.transaction.messaging.TransactionPostedEvent;
import com.corebank.transaction.repository.BankTransactionRepository;
import com.corebank.transaction.repository.LedgerEntryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every money movement lands here, and every movement produces a balanced pair of ledger
 * entries. Accounts are always loaded with a row lock, and when two customer accounts are
 * involved they are locked in a fixed order so that transfers running in opposite directions
 * cannot deadlock against each other.
 */
@Service
public class TransactionService {

    private final BankTransactionRepository transactions;
    private final LedgerEntryRepository entries;
    private final AccountService accountService;
    private final ReferenceGenerator referenceGenerator;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;

    public TransactionService(BankTransactionRepository transactions,
                              LedgerEntryRepository entries,
                              AccountService accountService,
                              ReferenceGenerator referenceGenerator,
                              ApplicationEventPublisher eventPublisher,
                              MeterRegistry meterRegistry) {
        this.transactions = transactions;
        this.entries = entries;
        this.accountService = accountService;
        this.referenceGenerator = referenceGenerator;
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;
    }

    /** Cash in at the counter: the bank holds more cash, and owes the customer more. */
    @Transactional
    public TransactionResponse deposit(UUID accountId, AmountRequest request, String idempotencyKey) {
        BigDecimal amount = Money.normalize(request.amount());
        String currency = currencyOf(request.currency());

        Account account = customerAccountForUpdate(accountId, currency);
        Account cash = accountService.cashAccount();

        BankTransaction transaction = newTransaction(
                TransactionType.DEPOSIT, amount, currency, request.description(), idempotencyKey);
        transaction.addEntry(cash, EntryDirection.DEBIT, amount);
        transaction.addEntry(account, EntryDirection.CREDIT, amount);

        return post(transaction);
    }

    /** Cash out at the counter: the customer claim falls, and so does the cash position. */
    @Transactional
    public TransactionResponse withdraw(UUID accountId, AmountRequest request, String idempotencyKey) {
        BigDecimal amount = Money.normalize(request.amount());
        String currency = currencyOf(request.currency());

        Account account = customerAccountForUpdate(accountId, currency);
        Account cash = accountService.cashAccount();

        BankTransaction transaction = newTransaction(
                TransactionType.WITHDRAWAL, amount, currency, request.description(), idempotencyKey);
        // The customer leg is applied first, so an insufficient-funds failure aborts the
        // posting before the cash position has been touched.
        transaction.addEntry(account, EntryDirection.DEBIT, amount);
        transaction.addEntry(cash, EntryDirection.CREDIT, amount);

        return post(transaction);
    }

    /** Book transfer between two accounts at this bank. No cash moves, so cash is not a leg. */
    @Transactional
    public TransactionResponse transfer(TransferRequest request, String idempotencyKey) {
        if (request.sourceAccountId().equals(request.destinationAccountId())) {
            throw new BusinessRuleException("SAME_ACCOUNT_TRANSFER",
                    "The source and destination accounts must differ");
        }
        BigDecimal amount = Money.normalize(request.amount());
        String currency = currencyOf(request.currency());

        // Take both row locks in a stable order regardless of transfer direction, so that a
        // simultaneous transfer the other way waits rather than deadlocking.
        List<UUID> lockOrder = List.of(request.sourceAccountId(), request.destinationAccountId())
                .stream()
                .sorted(Comparator.comparing(UUID::toString))
                .toList();
        lockOrder.forEach(accountService::requireForUpdate);

        Account source = customerAccountForUpdate(request.sourceAccountId(), currency);
        Account destination = customerAccountForUpdate(request.destinationAccountId(), currency);

        BankTransaction transaction = newTransaction(
                TransactionType.TRANSFER, amount, currency, request.description(), idempotencyKey);
        transaction.addEntry(source, EntryDirection.DEBIT, amount);
        transaction.addEntry(destination, EntryDirection.CREDIT, amount);

        return post(transaction);
    }

    /**
     * Undoes a posting by mirroring every leg it produced, as a new transaction of its own.
     *
     * <p>Nothing about the original is rewritten except its status: the ledger is append-only, so
     * a statement covering both postings shows the money moving and then moving back, which is
     * the honest record. It also means every downstream consumer of the ledger -- search, the
     * spending insights service, anything summing signed amounts -- nets out correctly with no
     * awareness that reversal exists, because the correcting legs are ordinary legs.
     *
     * <p>Both accounts of a transfer are locked in the same order {@link #transfer} uses, so a
     * reversal and a live transfer over the same pair queue behind one another instead of
     * deadlocking.
     */
    @Transactional
    public TransactionResponse reverse(String reference, ReversalRequest request, String idempotencyKey) {
        BankTransaction original = transactions.findByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", reference));
        original.assertReversible();

        original.getEntries().stream()
                .map(entry -> entry.getAccount().getId())
                .distinct()
                .sorted(Comparator.comparing(UUID::toString))
                .forEach(accountService::requireForUpdate);

        // A closed or frozen account still refuses the posting. Only the overdraft limit gives
        // way for a reversal -- see Account.applyEntry(..., allowOverdraw).
        original.getEntries().forEach(entry -> entry.getAccount().assertPostable());

        BankTransaction reversal = newTransaction(TransactionType.REVERSAL, original.getAmount(),
                original.getCurrency(), request.reason(), idempotencyKey);
        reversal.setReversalOf(original);
        original.getEntries().forEach(reversal::addReversingEntry);
        original.markReversed();

        return post(reversal);
    }

    @Transactional(readOnly = true)
    public TransactionResponse getByReference(String reference) {
        return transactions.findByReference(reference)
                .map(TransactionResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", reference));
    }

    @Transactional(readOnly = true)
    public Page<StatementLineResponse> statement(UUID accountId, Instant from, Instant to, Pageable pageable) {
        accountService.require(accountId);
        // The query takes concrete bounds; an untyped null parameter is not something
        // PostgreSQL can assign a type to.
        Instant lower = from == null ? Instant.EPOCH : from;
        Instant upper = to == null ? Instant.now() : to;
        if (lower.isAfter(upper)) {
            throw new BusinessRuleException("INVALID_DATE_RANGE", "The start of the range must not be after its end");
        }
        // Sorting belongs here rather than in the query: with an entity graph the ordering has
        // to travel on the Pageable for it to reach the count-limited SQL.
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "postedAt", "sequenceNo"));
        return entries.findStatement(accountId, lower, upper, sorted).map(StatementLineResponse::from);
    }

    private TransactionResponse post(BankTransaction transaction) {
        transaction.assertBalanced();
        BankTransaction saved = transactions.save(transaction);
        // The cached account detail (AccountService.get) is now stale for every account this
        // posting touched; the short TTL is a safety net, not the primary freshness mechanism.
        saved.getEntries().stream()
                .map(entry -> entry.getAccount().getId())
                .distinct()
                .forEach(accountService::evictCache);
        // Published now, but only actually sent to Kafka after this method's transaction
        // commits -- see TransactionEventPublisher.
        eventPublisher.publishEvent(TransactionPostedEvent.from(saved));
        recordMetrics(saved);
        return TransactionResponse.from(saved);
    }

    private void recordMetrics(BankTransaction transaction) {
        meterRegistry.counter("corebank.transactions.posted",
                        "type", transaction.getType().name(),
                        "currency", transaction.getCurrency())
                .increment();
        meterRegistry.summary("corebank.transactions.amount",
                        "type", transaction.getType().name(),
                        "currency", transaction.getCurrency())
                .record(transaction.getAmount().doubleValue());
    }

    private BankTransaction newTransaction(TransactionType type, BigDecimal amount, String currency,
                                           String description, String idempotencyKey) {
        BankTransaction transaction = new BankTransaction();
        transaction.setReference(referenceGenerator.next());
        transaction.setType(type);
        transaction.setStatus(TransactionStatus.POSTED);
        transaction.setAmount(amount);
        transaction.setCurrency(currency);
        transaction.setDescription(description);
        transaction.setIdempotencyKey(idempotencyKey);
        transaction.setPostedAt(Instant.now());
        return transaction;
    }

    private Account customerAccountForUpdate(UUID accountId, String currency) {
        Account account = accountService.requireForUpdate(accountId);
        if (!account.isCustomerAccount()) {
            throw new BusinessRuleException("INTERNAL_ACCOUNT",
                    "General-ledger accounts cannot be used through this endpoint");
        }
        account.assertPostable();
        account.assertCurrency(currency);
        return account;
    }

    private String currencyOf(String requested) {
        return requested == null ? Money.BASE_CURRENCY : requested;
    }
}
