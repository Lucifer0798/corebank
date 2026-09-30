package com.corebank.transaction.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corebank.account.domain.Account;
import com.corebank.account.domain.AccountClass;
import com.corebank.account.domain.AccountStatus;
import com.corebank.account.domain.AccountType;
import com.corebank.account.domain.EntryDirection;
import com.corebank.common.exception.BusinessRuleException;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BankTransactionTest {

    private BankTransaction transaction;
    private Account source;
    private Account destination;
    private Account dollarAccount;
    private Account rupeePosition;
    private Account dollarPosition;

    @BeforeEach
    void setUp() {
        transaction = new BankTransaction();
        transaction.setReference("TXN-20250417-TESTTEST");
        transaction.setType(TransactionType.TRANSFER);
        transaction.setAmount(new BigDecimal("100.00"));
        transaction.setCurrency("INR");
        transaction.setPostedAt(Instant.parse("2025-04-17T10:15:30Z"));

        source = account("100100000001", "500.00");
        destination = account("100100000002", "0.00");
        dollarAccount = inCurrency(account("100100000003", "0.00"), "USD");
        rupeePosition = internal(account("GL0000000010", "0.00"), "INR");
        dollarPosition = internal(account("GL0000000011", "0.00"), "USD");
    }

    private static Account inCurrency(Account account, String currency) {
        account.setCurrency(currency);
        return account;
    }

    private static Account internal(Account account, String currency) {
        account.setAccountClass(AccountClass.INTERNAL);
        account.setAccountType(AccountType.FX_POSITION_GL);
        account.setCurrency(currency);
        return account;
    }

    private static Account account(String number, String balance) {
        Account account = new Account();
        account.setAccountNumber(number);
        account.setAccountClass(AccountClass.CUSTOMER);
        account.setAccountType(AccountType.SAVINGS);
        account.setNormalBalance(EntryDirection.CREDIT);
        account.setCurrency("INR");
        account.setBalance(new BigDecimal(balance));
        account.setOverdraftLimit(BigDecimal.ZERO);
        account.setStatus(AccountStatus.ACTIVE);
        return account;
    }

    @Test
    @DisplayName("each leg records the balance it left behind, numbered in posting order")
    void entriesCaptureRunningBalances() {
        transaction.addEntry(source, EntryDirection.DEBIT, new BigDecimal("100.00"));
        transaction.addEntry(destination, EntryDirection.CREDIT, new BigDecimal("100.00"));

        assertThat(transaction.getEntries()).hasSize(2);
        assertThat(transaction.getEntries().get(0).getBalanceAfter()).isEqualByComparingTo("400.00");
        assertThat(transaction.getEntries().get(0).getSequenceNo()).isEqualTo(1);
        assertThat(transaction.getEntries().get(1).getBalanceAfter()).isEqualByComparingTo("100.00");
        assertThat(transaction.getEntries().get(1).getSequenceNo()).isEqualTo(2);
        assertThat(transaction.getEntries().get(1).getPostedAt()).isEqualTo(transaction.getPostedAt());
    }

    @Test
    @DisplayName("a signed amount reads negative on the side whose balance fell")
    void signedAmountFollowsTheAccountPerspective() {
        LedgerEntry debit = transaction.addEntry(source, EntryDirection.DEBIT, new BigDecimal("100.00"));
        LedgerEntry credit = transaction.addEntry(destination, EntryDirection.CREDIT, new BigDecimal("100.00"));

        assertThat(debit.signedAmount()).isEqualByComparingTo("-100.00");
        assertThat(credit.signedAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("a matched pair of legs balances")
    void balancedPostingPasses() {
        transaction.addEntry(source, EntryDirection.DEBIT, new BigDecimal("100.00"));
        transaction.addEntry(destination, EntryDirection.CREDIT, new BigDecimal("100.00"));

        assertThat(transaction.getEntries()).hasSize(2);
        transaction.assertBalanced();
    }

    @Test
    @DisplayName("a posting whose debits and credits differ is rejected before it can be written")
    void unbalancedPostingIsRejected() {
        transaction.addEntry(source, EntryDirection.DEBIT, new BigDecimal("100.00"));
        transaction.addEntry(destination, EntryDirection.CREDIT, new BigDecimal("60.00"));

        assertThatThrownBy(transaction::assertBalanced)
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("do not balance in INR");
    }

    @Test
    @DisplayName("a four-legged posting balances when each currency balances on its own")
    void aBalancedCrossCurrencyPostingPasses() {
        transaction.addEntry(source, EntryDirection.DEBIT, new BigDecimal("100.00"));
        transaction.addEntry(rupeePosition, EntryDirection.CREDIT, new BigDecimal("100.00"));
        transaction.addEntry(dollarPosition, EntryDirection.DEBIT, new BigDecimal("1.19"));
        transaction.addEntry(dollarAccount, EntryDirection.CREDIT, new BigDecimal("1.19"));

        transaction.assertBalanced();
    }

    @Test
    @DisplayName("a cross-currency posting whose converted side is wrong is rejected")
    void aCrossCurrencyPostingMustBalanceInEachCurrency() {
        // The assertion the per-currency rule exists for, and the one a total-only check cannot
        // make. Summed together these four legs come to 100 + 1000 on each side and pass, which
        // is a ledger cheerfully recording that 100 rupees became a thousand dollars. Only
        // grouping by currency first notices that the dollar side does not close.
        transaction.addEntry(source, EntryDirection.DEBIT, new BigDecimal("100.00"));
        transaction.addEntry(rupeePosition, EntryDirection.CREDIT, new BigDecimal("100.00"));
        transaction.addEntry(dollarPosition, EntryDirection.DEBIT, new BigDecimal("1000.00"));
        transaction.addEntry(dollarAccount, EntryDirection.CREDIT, new BigDecimal("1.19"));

        assertThatThrownBy(transaction::assertBalanced)
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("do not balance in USD");
    }

    @Test
    @DisplayName("legs that cancel across currencies but not within one are still rejected")
    void offsettingAcrossCurrenciesIsNotBalance() {
        // The purest form of the bug: debits and credits are equal in total -- 100 each side --
        // and yet neither currency closes. Adding rupees to dollars is not arithmetic.
        transaction.addEntry(source, EntryDirection.DEBIT, new BigDecimal("100.00"));
        transaction.addEntry(dollarAccount, EntryDirection.CREDIT, new BigDecimal("100.00"));

        assertThatThrownBy(transaction::assertBalanced)
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("a single-legged posting is rejected")
    void singleLegIsRejected() {
        transaction.addEntry(source, EntryDirection.DEBIT, new BigDecimal("100.00"));

        assertThatThrownBy(transaction::assertBalanced).isInstanceOf(BusinessRuleException.class);
    }
}
