package com.corebank.account.service;

import com.corebank.account.domain.Account;
import com.corebank.account.domain.AccountClass;
import com.corebank.account.domain.AccountStatus;
import com.corebank.account.domain.AccountType;
import com.corebank.account.domain.EntryDirection;
import com.corebank.account.dto.AccountResponse;
import com.corebank.account.dto.OpenAccountRequest;
import com.corebank.account.repository.AccountRepository;
import com.corebank.common.Money;
import com.corebank.common.SequenceNumberGenerator;
import com.corebank.common.exception.BusinessRuleException;
import com.corebank.common.exception.ResourceNotFoundException;
import com.corebank.config.CacheConfig;
import com.corebank.config.CoreBankProperties;
import com.corebank.customer.domain.Customer;
import com.corebank.customer.service.CustomerService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    static final String ACCOUNT_NUMBER_SEQUENCE = "account_number_seq";
    private static final int MAX_OPEN_ACCOUNTS_PER_CUSTOMER = 10;

    private final AccountRepository accounts;
    private final CustomerService customerService;
    private final SequenceNumberGenerator sequences;
    private final CoreBankProperties properties;
    private final List<AccountClosureCheck> closureChecks;

    public AccountService(AccountRepository accounts,
                          CustomerService customerService,
                          SequenceNumberGenerator sequences,
                          CoreBankProperties properties,
                          List<AccountClosureCheck> closureChecks) {
        this.accounts = accounts;
        this.customerService = customerService;
        this.sequences = sequences;
        this.properties = properties;
        this.closureChecks = closureChecks;
    }

    @Transactional
    public AccountResponse open(OpenAccountRequest request) {
        if (request.accountType() != AccountType.SAVINGS && request.accountType() != AccountType.CURRENT) {
            throw new BusinessRuleException("UNSUPPORTED_ACCOUNT_TYPE",
                    "Only SAVINGS and CURRENT accounts can be opened through the API");
        }

        Customer customer = customerService.require(request.customerId());
        if (!customer.canOpenAccounts()) {
            throw new BusinessRuleException("CUSTOMER_NOT_ELIGIBLE",
                    "Customer " + customer.getCustomerNumber() + " must be ACTIVE and KYC-verified to hold an account");
        }
        if (accounts.countOpenAccounts(customer.getId()) >= MAX_OPEN_ACCOUNTS_PER_CUSTOMER) {
            throw new BusinessRuleException("ACCOUNT_LIMIT_REACHED",
                    "A customer may hold at most " + MAX_OPEN_ACCOUNTS_PER_CUSTOMER + " open accounts");
        }

        BigDecimal overdraftLimit = Money.orZero(request.overdraftLimit());
        if (request.accountType() == AccountType.SAVINGS && Money.isPositive(overdraftLimit)) {
            throw new BusinessRuleException("OVERDRAFT_NOT_ALLOWED", "A savings account cannot carry an overdraft");
        }

        Account account = new Account();
        account.setAccountNumber(nextAccountNumber());
        account.setCustomer(customer);
        account.setAccountClass(AccountClass.CUSTOMER);
        account.setAccountType(request.accountType());
        // A customer account is a liability of the bank: money in is a credit.
        account.setNormalBalance(EntryDirection.CREDIT);
        String currency = request.currency() == null ? Money.BASE_CURRENCY : request.currency();
        // Refused at the door. Only the ISO shape used to be checked, so an account could be opened
        // in any three letters -- and one in a currency the bank keeps no cash account in could
        // never receive a deposit, nor be converted into, and was simply dead from the moment it
        // existed. Cash is the test because it is the one internal account every usable currency
        // must have: without it nothing can be paid in.
        if (!accounts.existsByAccountClassAndAccountTypeAndCurrency(
                AccountClass.INTERNAL, AccountType.CASH_GL, currency)) {
            throw new BusinessRuleException("CURRENCY_NOT_SUPPORTED",
                    "This bank does not hold accounts in " + currency);
        }
        account.setCurrency(currency);
        account.setBalance(Money.ZERO);
        account.setOverdraftLimit(overdraftLimit);
        account.setStatus(AccountStatus.ACTIVE);
        account.setOpenedAt(Instant.now());

        return AccountResponse.from(accounts.save(account));
    }

    /**
     * Account detail is read far more often than it changes, so it is cached for a short TTL.
     * Every path that changes a balance or a status calls {@link #evictCache(UUID)} straight
     * after, so the TTL only ever covers a gap the eviction missed -- it is a safety net, not
     * the primary freshness mechanism.
     *
     * <p>Deliberately not {@code sync = true}: that would stop concurrent misses on a hot account
     * from all reaching Postgres at once, but {@code CacheAspectSupport}'s synchronous path calls
     * {@code Cache.get(key, valueLoader)} directly, which does not consult this class's
     * {@link CacheErrorHandler} the way a plain {@code cache.get(key)} lookup does -- confirmed by
     * this repo's own tests: turning it on made a Redis outage in the mocked-JWT suite surface as
     * a bare 500 instead of falling through to the database, exactly the failure mode
     * {@link CacheConfig}'s error handler exists to prevent. Redis staying advisory, never a
     * source of truth, matters more here than closing a stampede window that a 30s TTL and a
     * single corebank-app replica already keep narrow.
     */
    @Cacheable(cacheNames = CacheConfig.ACCOUNTS_CACHE, key = "#accountId")
    @Transactional(readOnly = true)
    public AccountResponse get(UUID accountId) {
        return AccountResponse.from(require(accountId));
    }

    @Transactional(readOnly = true)
    public Page<AccountResponse> listForCustomer(UUID customerId, Pageable pageable) {
        customerService.require(customerId);
        return accounts.findByCustomerId(customerId, pageable).map(AccountResponse::from);
    }

    @CacheEvict(cacheNames = CacheConfig.ACCOUNTS_CACHE, key = "#accountId")
    @Transactional
    public AccountResponse changeStatus(UUID accountId, AccountStatus target) {
        // Locked, so that what the closure checks below find is still true at commit: placing a
        // hold, or setting up or resuming a standing instruction, takes this same row lock first.
        Account account = requireForUpdate(accountId);
        if (!account.isCustomerAccount()) {
            throw new BusinessRuleException("INTERNAL_ACCOUNT", "General-ledger accounts cannot be changed");
        }
        if (account.getStatus() == AccountStatus.CLOSED) {
            throw new BusinessRuleException("ACCOUNT_CLOSED", "A closed account cannot be reopened");
        }
        if (target == AccountStatus.CLOSED && Money.isPositive(account.getBalance().abs())) {
            throw new BusinessRuleException("BALANCE_NOT_ZERO",
                    "Account " + account.getAccountNumber() + " must be emptied before it is closed");
        }
        if (target == AccountStatus.CLOSED) {
            assertNothingOutstanding(account);
        }

        account.setStatus(target);
        account.setClosedAt(target == AccountStatus.CLOSED ? Instant.now() : null);
        return AccountResponse.from(account);
    }

    /**
     * Refuses to close over anything another feature still owes or is owed through this account --
     * see {@link AccountClosureCheck}. Everything outstanding is listed at once, so staff clear it
     * in one pass rather than discovering it one refusal at a time.
     */
    private void assertNothingOutstanding(Account account) {
        List<String> outstanding = closureChecks.stream()
                .map(check -> check.outstandingFor(account.getId()))
                .flatMap(Optional::stream)
                .toList();
        if (!outstanding.isEmpty()) {
            throw new BusinessRuleException("CLOSURE_BLOCKED",
                    "Account " + account.getAccountNumber() + " still has " + String.join(" and ", outstanding)
                            + "; release or cancel them before closing it");
        }
    }

    /** Evicts the cached detail for one account. Called after any posting that touches its balance. */
    @CacheEvict(cacheNames = CacheConfig.ACCOUNTS_CACHE, key = "#accountId")
    public void evictCache(UUID accountId) {
        // Body intentionally empty; @CacheEvict does the work.
    }

    @Transactional(readOnly = true)
    public Account require(UUID accountId) {
        return accounts.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
    }

    /** Loads an account with a row lock; used by every path that moves money. */
    public Account requireForUpdate(UUID accountId) {
        return accounts.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
    }

    public Account requireInternalAccount(String accountNumber) {
        return accounts.findByAccountNumberForUpdate(accountNumber)
                .orElseThrow(() -> new IllegalStateException(
                        "General-ledger account " + accountNumber + " is missing; check the Flyway baseline"));
    }

    /**
     * The internal account of one type in one currency.
     *
     * <p>Every posting that touches an internal account goes through here, by type and currency,
     * rather than naming an account number. That is the fix for the bug V12 describes: cash and
     * interest expense were reached by number, and the numbers were rupee accounts, so a dollar
     * posting got a rupee contra leg and could not balance. Looked up by currency, the contra leg is
     * in the customer's currency by construction, and adding a currency is a migration rather than an
     * edit to every posting path.
     *
     * <p>Missing is a business refusal rather than a server error: it means the bank does not deal
     * in that currency, which a caller can be told. More than one is a configuration fault, refused
     * by name -- choosing between two rupee cash accounts arbitrarily would be worse than stopping.
     */
    public Account internalAccount(AccountType type, String currency) {
        List<Account> found = accounts.findInternalForUpdate(type, currency);
        if (found.isEmpty()) {
            throw new BusinessRuleException("CURRENCY_NOT_SUPPORTED",
                    "This bank holds no " + type + " account in " + currency);
        }
        if (found.size() > 1) {
            throw new IllegalStateException("There are " + found.size() + " " + type + " accounts in "
                    + currency + "; the ledger cannot choose between them");
        }
        return found.getFirst();
    }

    /** The bank's position in one currency. See {@link #internalAccount}. */
    public Account fxPositionAccount(String currency) {
        return internalAccount(AccountType.FX_POSITION_GL, currency);
    }

    /** Cash in one currency -- the contra leg of a deposit or withdrawal in that currency. */
    public Account cashAccount(String currency) {
        return internalAccount(AccountType.CASH_GL, currency);
    }

    /** The bank's cost of paying interest in one currency. */
    public Account interestExpenseAccount(String currency) {
        return internalAccount(AccountType.INTEREST_EXPENSE_GL, currency);
    }

    /**
     * Rupee cash. Kept for callers that genuinely mean the base currency; anything posting against a
     * customer account must use {@link #cashAccount(String)} with that account's currency instead.
     */
    public Account cashAccount() {
        return cashAccount(com.corebank.common.Money.BASE_CURRENCY);
    }

    private String nextAccountNumber() {
        return properties.account().numberPrefix() + "%08d".formatted(sequences.next(ACCOUNT_NUMBER_SEQUENCE));
    }
}
