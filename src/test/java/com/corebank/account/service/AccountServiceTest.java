package com.corebank.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.corebank.account.domain.Account;
import com.corebank.account.domain.AccountClass;
import com.corebank.account.domain.AccountStatus;
import com.corebank.account.domain.AccountType;
import com.corebank.account.dto.OpenAccountRequest;
import com.corebank.account.repository.AccountRepository;
import com.corebank.common.SequenceNumberGenerator;
import com.corebank.common.exception.BusinessRuleException;
import com.corebank.config.CoreBankProperties;
import com.corebank.customer.domain.Customer;
import com.corebank.customer.domain.CustomerStatus;
import com.corebank.customer.domain.KycStatus;
import com.corebank.customer.service.CustomerService;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The two rules in {@link AccountService} that are pure policy rather than bookkeeping: how many
 * open accounts a customer may hold, and the fact that closing an account is final. Both are a
 * single comparison, which is exactly the kind of thing an off-by-one slips into -- {@code >=}
 * quietly becoming {@code >} lets an eleventh account through, and dropping the status guard lets
 * a closed account come back to life. Neither shows up as an error anywhere; the request just
 * succeeds when it shouldn't.
 *
 * <p>Mocks rather than a live context because the boundary is the point: {@code countOpenAccounts}
 * returning exactly the limit, and exactly one below it, are two assertions that would otherwise
 * need ten real accounts opened over HTTP to reach.
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    private static final int LIMIT = 10;

    @Mock
    private AccountRepository accounts;

    @Mock
    private CustomerService customerService;

    @Mock
    private SequenceNumberGenerator sequences;

    private AccountService accountService;
    private Customer customer;

    @BeforeEach
    void setUp() {
        CoreBankProperties properties = new CoreBankProperties(
                new CoreBankProperties.Ledger("GL0000000001", "GL0000000002"),
                new CoreBankProperties.AccountSettings("1001"),
                null, null, null, null);
        accountService = new AccountService(accounts, customerService, sequences, properties);

        customer = new Customer();
        customer.setId(UUID.randomUUID());
        customer.setCustomerNumber("CUST00000001");
        customer.setStatus(CustomerStatus.ACTIVE);
        customer.setKycStatus(KycStatus.VERIFIED);
    }

    private OpenAccountRequest openSavings() {
        return new OpenAccountRequest(customer.getId(), AccountType.SAVINGS, "INR", BigDecimal.ZERO);
    }

    /**
     * Only what {@code open()} needs before it reaches the limit check. Stubbed per-test rather
     * than in setUp so Mockito's strict stubbing stays on: a rejection test that never gets as
     * far as saving should fail loudly if it was handed a save stub it didn't use.
     */
    private void stubEligibleCustomer() {
        when(customerService.require(any(UUID.class))).thenReturn(customer);
    }

    @Test
    @DisplayName("a customer one account below the limit can still open another")
    void oneBelowTheLimitIsAllowed() {
        stubEligibleCustomer();
        when(sequences.next(AccountService.ACCOUNT_NUMBER_SEQUENCE)).thenReturn(42L);
        when(accounts.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(accounts.countOpenAccounts(customer.getId())).thenReturn((long) LIMIT - 1);

        assertThat(accountService.open(openSavings()).accountNumber()).isEqualTo("100100000042");
        verify(accounts).save(any(Account.class));
    }

    @Test
    @DisplayName("a customer already at the limit cannot open another")
    void atTheLimitIsRejected() {
        stubEligibleCustomer();
        when(accounts.countOpenAccounts(customer.getId())).thenReturn((long) LIMIT);

        assertThatThrownBy(() -> accountService.open(openSavings()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("at most " + LIMIT + " open accounts")
                .extracting(ex -> ((BusinessRuleException) ex).getCode())
                .isEqualTo("ACCOUNT_LIMIT_REACHED");
        // Nothing may reach the database once the rule rejects the request.
        verify(accounts, never()).save(any(Account.class));
    }

    @Test
    @DisplayName("a customer somehow past the limit cannot open another either")
    void pastTheLimitIsRejected() {
        // Closing and reopening accounts could leave a customer above the limit; `>=` handles
        // that, a bare `==` would not.
        stubEligibleCustomer();
        when(accounts.countOpenAccounts(customer.getId())).thenReturn((long) LIMIT + 3);

        assertThatThrownBy(() -> accountService.open(openSavings()))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getCode())
                .isEqualTo("ACCOUNT_LIMIT_REACHED");
    }

    @Test
    @DisplayName("a closed account cannot be reopened")
    void closedAccountCannotBeReopened() {
        Account closed = customerAccount(AccountStatus.CLOSED);
        UUID accountId = closed.getId();
        when(accounts.findById(accountId)).thenReturn(java.util.Optional.of(closed));

        assertThatThrownBy(() -> accountService.changeStatus(accountId, AccountStatus.ACTIVE))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("closed account cannot be reopened")
                .extracting(ex -> ((BusinessRuleException) ex).getCode())
                .isEqualTo("ACCOUNT_CLOSED");
        assertThat(closed.getStatus()).isEqualTo(AccountStatus.CLOSED);
    }

    @Test
    @DisplayName("a frozen account can still be returned to service")
    void frozenAccountCanBeUnfrozen() {
        // The mirror of the test above: the guard has to reject CLOSED specifically, not any
        // status that happens to be non-ACTIVE.
        Account frozen = customerAccount(AccountStatus.FROZEN);
        when(accounts.findById(frozen.getId())).thenReturn(java.util.Optional.of(frozen));

        assertThat(accountService.changeStatus(frozen.getId(), AccountStatus.ACTIVE).status())
                .isEqualTo(AccountStatus.ACTIVE);
    }

    private Account customerAccount(AccountStatus status) {
        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setAccountNumber("100100000042");
        account.setCustomer(customer);
        account.setAccountClass(AccountClass.CUSTOMER);
        account.setAccountType(AccountType.SAVINGS);
        account.setCurrency("INR");
        account.setBalance(BigDecimal.ZERO);
        account.setOverdraftLimit(BigDecimal.ZERO);
        account.setStatus(status);
        return account;
    }
}
