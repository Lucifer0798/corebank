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
                .hasMessageContaining("do not equal");
    }

    @Test
    @DisplayName("a single-legged posting is rejected")
    void singleLegIsRejected() {
        transaction.addEntry(source, EntryDirection.DEBIT, new BigDecimal("100.00"));

        assertThatThrownBy(transaction::assertBalanced).isInstanceOf(BusinessRuleException.class);
    }
}
