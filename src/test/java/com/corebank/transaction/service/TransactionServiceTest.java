package com.corebank.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
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
import com.corebank.common.exception.ConflictException;
import com.corebank.common.exception.InsufficientFundsException;
import com.corebank.common.exception.ResourceNotFoundException;
import com.corebank.transaction.domain.BankTransaction;
import com.corebank.transaction.domain.TransactionStatus;
import com.corebank.transaction.domain.TransactionType;
import com.corebank.transaction.dto.AmountRequest;
import com.corebank.transaction.dto.ReversalRequest;
import com.corebank.transaction.dto.TransactionResponse;
import com.corebank.transaction.dto.TransferRequest;
import com.corebank.transaction.messaging.TransactionPostedEvent;
import com.corebank.transaction.repository.BankTransactionRepository;
import com.corebank.transaction.repository.LedgerEntryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Exercises the posting rules with the repositories mocked out, so the assertions are about
 * which legs get produced rather than about persistence.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransactionServiceTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CASH_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private BankTransactionRepository transactions;

    @Mock
    private LedgerEntryRepository entries;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private AccountService accountService;

    @Mock
    private ReferenceGenerator referenceGenerator;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    // A real registry, not a mock: MeterRegistry.counter/summary return live meters that
    // increment() and record() call directly, which a bare mock would just return null for.
    @Spy
    private MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @InjectMocks
    private TransactionService transactionService;

    private Account customer;
    private Account cash;

    @BeforeEach
    void setUp() {
        customer = customerAccount(ACCOUNT_ID, "100100000001", "1000.00", "0.00");
        cash = new Account();
        // Given an id like any real account: reversal locks every account a posting touched, and
        // it locks them by id, so the cash leg of a deposit has to be addressable too.
        cash.setId(CASH_ID);
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
        verify(eventPublisher).publishEvent(org.mockito.ArgumentMatchers.any(TransactionPostedEvent.class));
        assertThat(meterRegistry.counter("corebank.transactions.posted", "type", "DEPOSIT", "currency", "INR")
                .count()).isEqualTo(1.0);
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
        when(accountService.requireForUpdate(CASH_ID)).thenReturn(cash);

        assertThatThrownBy(() -> transactionService.deposit(
                CASH_ID, new AmountRequest(new BigDecimal("10.00"), "INR", null), "key-8"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("General-ledger");
    }

    // --- Reversal ------------------------------------------------------------------------
    //
    // REVERSED was a legal transaction status from the first migration onward and no code path
    // could ever produce one. These are the rules that path has to obey.

    /**
     * Posts a genuine deposit through the service and hands back the transaction it wrote, so the
     * reversal tests mirror real legs produced by real code rather than a hand-built fixture whose
     * shape the production path might have drifted away from.
     */
    private BankTransaction postedDeposit(String amount) {
        transactionService.deposit(
                ACCOUNT_ID, new AmountRequest(new BigDecimal(amount), "INR", "Counter"), "post-key");

        ArgumentCaptor<BankTransaction> captor = ArgumentCaptor.forClass(BankTransaction.class);
        verify(transactions).save(captor.capture());
        BankTransaction original = captor.getValue();
        when(transactions.findByReference(original.getReference())).thenReturn(Optional.of(original));
        // The reversal needs a reference of its own; the generator is otherwise pinned to one value.
        when(referenceGenerator.next()).thenReturn("TXN-20250417-REVERSAL");
        return original;
    }

    private TransactionResponse reverse(BankTransaction original) {
        return transactionService.reverse(
                original.getReference(), new ReversalRequest("Keyed twice at branch 004"), "rev-key");
    }

    @Test
    @DisplayName("a reversal mirrors every leg and puts both balances back")
    void reversalMirrorsEveryLegAndRestoresBalances() {
        BankTransaction original = postedDeposit("250.00");
        assertThat(customer.getBalance()).isEqualByComparingTo("1250.00");

        TransactionResponse reversal = reverse(original);

        assertThat(reversal.type()).isEqualTo(TransactionType.REVERSAL);
        assertThat(reversal.reversalOf()).isEqualTo(original.getReference());
        assertThat(reversal.amount()).isEqualByComparingTo("250.00");

        // The deposit debited cash and credited the customer; the correction does the opposite,
        // leg for leg and in the same order.
        assertThat(reversal.legs()).hasSize(2);
        assertThat(reversal.legs().get(0).accountNumber()).isEqualTo("GL0000000001");
        assertThat(reversal.legs().get(0).direction()).isEqualTo(EntryDirection.CREDIT);
        assertThat(reversal.legs().get(1).accountNumber()).isEqualTo("100100000001");
        assertThat(reversal.legs().get(1).direction()).isEqualTo(EntryDirection.DEBIT);

        assertThat(customer.getBalance()).isEqualByComparingTo("1000.00");
        assertThat(cash.getBalance()).isEqualByComparingTo("50000.00");
        assertThat(original.getStatus())
                .describedAs("the original is marked, not deleted -- both postings stay on the statement")
                .isEqualTo(TransactionStatus.REVERSED);
    }

    @Test
    @DisplayName("a reversal may take an account past its overdraft limit")
    void reversalMayOverdrawTheAccount() {
        // The case the overdraft guard would otherwise make uncorrectable: money is deposited in
        // error, the customer spends nearly all of it, and only then is the error noticed. If the
        // reversal were refused here the ledger would be permanently wrong about a movement that
        // should never have happened, so it goes through and the shortfall becomes a debt.
        BankTransaction erroneousDeposit = postedDeposit("250.00");
        transactionService.withdraw(
                ACCOUNT_ID, new AmountRequest(new BigDecimal("1200.00"), "INR", "Spent"), "spend-key");
        assertThat(customer.getBalance()).isEqualByComparingTo("50.00");
        assertThat(customer.getOverdraftLimit())
                .describedAs("no agreed overdraft, so an ordinary posting could not go below zero")
                .isEqualByComparingTo("0.00");

        reverse(erroneousDeposit);

        assertThat(customer.getBalance()).isEqualByComparingTo("-200.00");
    }

    @Test
    @DisplayName("a frozen account still refuses a reversal")
    void frozenAccountStillRefusesAReversal() {
        // The bypass above is narrow: it covers the overdraft limit and nothing else. A freeze is
        // a deliberate operational decision, and lifting one is its own audited act.
        BankTransaction original = postedDeposit("250.00");
        customer.setStatus(AccountStatus.FROZEN);

        assertThatThrownBy(() -> reverse(original))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("frozen");

        assertThat(customer.getBalance()).isEqualByComparingTo("1250.00");
        assertThat(original.getStatus()).isEqualTo(TransactionStatus.POSTED);
    }

    @Test
    @DisplayName("a reversal cannot itself be reversed")
    void aReversalCannotItselfBeReversed() {
        BankTransaction original = postedDeposit("250.00");
        reverse(original);

        ArgumentCaptor<BankTransaction> captor = ArgumentCaptor.forClass(BankTransaction.class);
        verify(transactions, atLeastOnce()).save(captor.capture());
        BankTransaction reversal = captor.getAllValues().getLast();
        when(transactions.findByReference(reversal.getReference())).thenReturn(Optional.of(reversal));

        assertThatThrownBy(() -> transactionService.reverse(
                reversal.getReference(), new ReversalRequest("Changed my mind"), "rev-key-2"))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getCode())
                .isEqualTo("REVERSAL_NOT_REVERSIBLE");
    }

    @Test
    @DisplayName("a transaction cannot be reversed twice")
    void aTransactionCannotBeReversedTwice() {
        BankTransaction original = postedDeposit("250.00");
        reverse(original);
        assertThat(customer.getBalance()).isEqualByComparingTo("1000.00");

        // A conflict rather than a rule violation: this was reversible a moment ago. It is also
        // what a client retrying without an Idempotency-Key looks like.
        assertThatThrownBy(() -> reverse(original))
                .isInstanceOf(ConflictException.class)
                .extracting(ex -> ((ConflictException) ex).getCode())
                .isEqualTo("ALREADY_REVERSED");

        assertThat(customer.getBalance())
                .describedAs("the second attempt must not move money again")
                .isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("reversing a reference that does not exist is not found")
    void reversingAnUnknownReferenceIsNotFound() {
        when(transactions.findByReference("TXN-NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.reverse(
                "TXN-NOPE", new ReversalRequest("Whatever"), "rev-key-3"))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(transactions, never()).save(any());
    }
}
