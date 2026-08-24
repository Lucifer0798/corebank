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

    public AccountService(AccountRepository accounts,
                          CustomerService customerService,
                          SequenceNumberGenerator sequences,
                          CoreBankProperties properties) {
        this.accounts = accounts;
        this.customerService = customerService;
        this.sequences = sequences;
        this.properties = properties;
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
        account.setCurrency(request.currency() == null ? Money.BASE_CURRENCY : request.currency());
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

    @Transactional(readOnly = true)
    public List<AccountResponse> listForCustomer(UUID customerId) {
        return accounts.findByCustomerId(customerId).stream().map(AccountResponse::from).toList();
    }

    @CacheEvict(cacheNames = CacheConfig.ACCOUNTS_CACHE, key = "#accountId")
    @Transactional
    public AccountResponse changeStatus(UUID accountId, AccountStatus target) {
        Account account = require(accountId);
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

        account.setStatus(target);
        account.setClosedAt(target == AccountStatus.CLOSED ? Instant.now() : null);
        return AccountResponse.from(account);
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

    public Account cashAccount() {
        return requireInternalAccount(properties.ledger().cashAccountNumber());
    }

    private String nextAccountNumber() {
        return properties.account().numberPrefix() + "%08d".formatted(sequences.next(ACCOUNT_NUMBER_SEQUENCE));
    }
}
