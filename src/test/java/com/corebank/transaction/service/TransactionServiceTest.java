package com.corebank.transaction.service;

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
import com.corebank.account.domain.EntryDirection;
import com.corebank.account.service.AccountService;
import com.corebank.common.exception.BusinessRuleException;
import com.corebank.common.exception.InsufficientFundsException;
import com.corebank.transaction.domain.BankTransaction;
import com.corebank.transaction.domain.TransactionType;
import com.corebank.transaction.dto.AmountRequest;
import com.corebank.transaction.dto.TransactionResponse;
import com.corebank.transaction.dto.TransferRequest;
import com.corebank.transaction.repository.BankTransactionRepository;
import com.corebank.transaction.repository.LedgerEntryRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Exercises the posting rules with the repositories mocked out, so the assertions are about
 * which legs get produced rather than about persistence.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransactionServiceTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private BankTransactionRepository transactions;

    @Mock
    private LedgerEntryRepository entries;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private AccountService accountService;

    @Mock
    private ReferenceGenerator referenceGenerator;

    @InjectMocks
    private TransactionService transactionService;

    private Account customer;
    private Account cash;

    @BeforeEach
    void setUp() {
        customer = customerAccount(ACCOUNT_ID, "100100000001", "1000.00", "0.00");
        cash = new Account();
        cash.setAccountNumber("GL0000000001");
        cash.setAccountClass(AccountClass.INTERNAL);
        cash.setAccountType(AccountType.CASH_GL);
        cash.setNormalBalance(EntryDirection.DEBIT);
        cash.setCurrency("INR");
        cash.setBalance(new BigDecimal("50000.00"));
        cash.setOverdraftLimit(BigDecimal.ZERO);
        cash.setStatus(AccountStatus.ACTIVE);

        when(referenceGenerator.next()).thenReturn("TXN-20250417-TESTTEST");
        when(accountService.requireForUpdate(ACCOUNT_ID)).thenReturn(customer);
        when(accountService.cashAccount()).thenReturn(cash);
        when(transactions.save(any(BankTransaction.class))).thenAnswer(call -> call.getArgument(0));
    }

    private static Account customerAccount(UUID id, String number, String balance, String overdraft) {
        Account account = new Account();
        account.setId(id);
        account.setAccountNumber(number);
        account.setAccountClass(AccountClass.CUSTOMER);
        account.setAccountType(AccountType.CURRENT);
        account.setNormalBalance(EntryDirection.CREDIT);
        account.setCurrency("INR");
        account.setBalance(new BigDecimal(balance));
        account.setOverdraftLimit(new BigDecimal(overdraft));
        account.setStatus(AccountStatus.ACTIVE);
        return account;
    }

    @Test
    @DisplayName("a deposit debits cash and credits the customer")
    void depositProducesBalancedLegs() {
        TransactionResponse response = transactionService.deposit(
                ACCOUNT_ID, new AmountRequest(new BigDecimal("250.00"), "INR", "Counter"), "key-1");

        assertThat(response.type()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(response.legs()).hasSize(2);
        assertThat(response.legs().get(0).accountNumber()).isEqualTo("GL0000000001");
        assertThat(response.legs().get(0).direction()).isEqualTo(EntryDirection.DEBIT);
        assertThat(response.legs().get(1).direction()).isEqualTo(EntryDirection.CREDIT);
        assertThat(response.legs().get(1).balanceAfter()).isEqualByComparingTo("1250.00");
        assertThat(cash.getBalance()).isEqualByComparingTo("50250.00");
    }

    @Test
    @DisplayName("a withdrawal debits the customer and credits cash")
    void withdrawalProducesBalancedLegs() {
        TransactionResponse response = transactionService.withdraw(
                ACCOUNT_ID, new AmountRequest(new BigDecimal("400.00"), "INR", "ATM"), "key-2");

        assertThat(response.type()).isEqualTo(TransactionType.WITHDRAWAL);
        assertThat(response.legs().get(0).direction()).isEqualTo(EntryDirection.DEBIT);
        assertThat(response.legs().get(0).balanceAfter()).isEqualByComparingTo("600.00");
        assertThat(cash.getBalance()).isEqualByComparingTo("49600.00");
    }

    @Test
    @DisplayName("an unaffordable withdrawal is refused and nothing is written")
    void withdrawalBeyondAvailableBalanceIsRefused() {
        assertThatThrownBy(() -> transactionService.withdraw(
                ACCOUNT_ID, new AmountRequest(new BigDecimal("5000.00"), "INR", null), "key-3"))
                .isInstanceOf(InsufficientFundsException.class);

        verify(transactions, never()).save(any());
        assertThat(customer.getBalance()).isEqualByComparingTo("1000.00");
        assertThat(cash.getBalance())
                .describedAs("the cash leg must not be applied when the customer leg fails")
                .isEqualByComparingTo("50000.00");
    }

    @Test
    @DisplayName("a transfer moves value between two customer accounts without touching cash")
    void transferMovesBetweenCustomerAccounts() {
        Account destination = customerAccount(OTHER_ID, "100100000002", "0.00", "0.00");
        when(accountService.requireForUpdate(OTHER_ID)).thenReturn(destination);

        TransactionResponse response = transactionService.transfer(
                new TransferRequest(ACCOUNT_ID, OTHER_ID, new BigDecimal("300.00"), "INR", "Rent"), "key-4");

        assertThat(response.type()).isEqualTo(TransactionType.TRANSFER);
        assertThat(customer.getBalance()).isEqualByComparingTo("700.00");
        assertThat(destination.getBalance()).isEqualByComparingTo("300.00");
        assertThat(cash.getBalance()).isEqualByComparingTo("50000.00");
    }

    @Test
    @DisplayName("a transfer to the same account is refused")
    void transferToSelfIsRefused() {
        assertThatThrownBy(() -> transactionService.transfer(
                new TransferRequest(ACCOUNT_ID, ACCOUNT_ID, new BigDecimal("10.00"), "INR", null), "key-5"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("must differ");
    }

    @Test
    @DisplayName("a frozen account rejects a deposit")
    void frozenAccountRejectsDeposit() {
        customer.setStatus(AccountStatus.FROZEN);

        assertThatThrownBy(() -> transactionService.deposit(
                ACCOUNT_ID, new AmountRequest(new BigDecimal("10.00"), "INR", null), "key-6"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("frozen");

        verify(transactions, never()).save(any());
    }

    @Test
    @DisplayName("a posting in the wrong currency is refused")
    void currencyMismatchIsRefused() {
        assertThatThrownBy(() -> transactionService.deposit(
                ACCOUNT_ID, new AmountRequest(new BigDecimal("10.00"), "USD", null), "key-7"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("USD");
    }

    @Test
    @DisplayName("the general-ledger cash account cannot be used through the customer endpoints")
    void internalAccountsAreNotAddressable() {
        UUID cashId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        cash.setId(cashId);
        when(accountService.requireForUpdate(cashId)).thenReturn(cash);

        assertThatThrownBy(() -> transactionService.deposit(
                cashId, new AmountRequest(new BigDecimal("10.00"), "INR", null), "key-8"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("General-ledger");
    }
}
