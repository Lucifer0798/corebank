package com.corebank.account.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corebank.common.exception.BusinessRuleException;
import com.corebank.common.exception.InsufficientFundsException;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The balance rules live on the entity, so they are tested directly rather than through
 * a service. These are the invariants the ledger depends on.
 */
class AccountTest {

    private static Account customerAccount(String balance, String overdraft) {
        Account account = new Account();
        account.setAccountNumber("100100000001");
        account.setAccountClass(AccountClass.CUSTOMER);
        account.setAccountType(AccountType.CURRENT);
        account.setNormalBalance(EntryDirection.CREDIT);
        account.setCurrency("INR");
        account.setBalance(new BigDecimal(balance));
        account.setOverdraftLimit(new BigDecimal(overdraft));
        account.setStatus(AccountStatus.ACTIVE);
        return account;
    }

    private static Account cashAccount(String balance) {
        Account account = new Account();
        account.setAccountNumber("GL0000000001");
        account.setAccountClass(AccountClass.INTERNAL);
        account.setAccountType(AccountType.CASH_GL);
        account.setNormalBalance(EntryDirection.DEBIT);
        account.setCurrency("INR");
        account.setBalance(new BigDecimal(balance));
        account.setOverdraftLimit(BigDecimal.ZERO);
        account.setStatus(AccountStatus.ACTIVE);
        return account;
    }

    @Nested
    @DisplayName("applying a ledger entry")
    class ApplyEntry {

        @Test
        @DisplayName("credits raise a customer balance because the bank owes more")
        void creditRaisesCustomerBalance() {
            Account account = customerAccount("100.00", "0.00");

            BigDecimal result = account.applyEntry(EntryDirection.CREDIT, new BigDecimal("250.00"));

            assertThat(result).isEqualByComparingTo("350.00");
            assertThat(account.getBalance()).isEqualByComparingTo("350.00");
        }

        @Test
        @DisplayName("debits lower a customer balance")
        void debitLowersCustomerBalance() {
            Account account = customerAccount("100.00", "0.00");

            assertThat(account.applyEntry(EntryDirection.DEBIT, new BigDecimal("40.00")))
                    .isEqualByComparingTo("60.00");
        }

        @Test
        @DisplayName("debits raise the cash account, whose normal balance is the other way round")
        void debitRaisesAssetBalance() {
            Account cash = cashAccount("1000.00");

            assertThat(cash.applyEntry(EntryDirection.DEBIT, new BigDecimal("500.00")))
                    .isEqualByComparingTo("1500.00");
            assertThat(cash.applyEntry(EntryDirection.CREDIT, new BigDecimal("200.00")))
                    .isEqualByComparingTo("1300.00");
        }

        @Test
        @DisplayName("a withdrawal beyond the available balance is refused")
        void refusesOverdrawing() {
            Account account = customerAccount("100.00", "0.00");

            assertThatThrownBy(() -> account.applyEntry(EntryDirection.DEBIT, new BigDecimal("100.01")))
                    .isInstanceOf(InsufficientFundsException.class)
                    .hasMessageContaining("100.00 available");

            assertThat(account.getBalance())
                    .describedAs("a refused entry must leave the balance untouched")
                    .isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("an overdraft extends how far a balance may fall, and no further")
        void overdraftExtendsAvailableBalance() {
            Account account = customerAccount("100.00", "500.00");

            assertThat(account.availableBalance()).isEqualByComparingTo("600.00");
            assertThat(account.applyEntry(EntryDirection.DEBIT, new BigDecimal("600.00")))
                    .isEqualByComparingTo("-500.00");
            assertThatThrownBy(() -> account.applyEntry(EntryDirection.DEBIT, new BigDecimal("0.01")))
                    .isInstanceOf(InsufficientFundsException.class);
        }

        @Test
        @DisplayName("the internal cash account may run negative; the bank funds it")
        void internalAccountsMayGoNegative() {
            Account cash = cashAccount("0.00");

            assertThat(cash.applyEntry(EntryDirection.CREDIT, new BigDecimal("750.00")))
                    .isEqualByComparingTo("-750.00");
        }
    }

    @Nested
    @DisplayName("posting guards")
    class PostingGuards {

        @Test
        void frozenAccountsRejectPostings() {
            Account account = customerAccount("100.00", "0.00");
            account.setStatus(AccountStatus.FROZEN);

            assertThatThrownBy(account::assertPostable)
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("frozen");
        }

        @Test
        void closedAccountsRejectPostings() {
            Account account = customerAccount("0.00", "0.00");
            account.setStatus(AccountStatus.CLOSED);

            assertThatThrownBy(account::assertPostable)
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("closed");
        }

        @Test
        void currencyMustMatch() {
            Account account = customerAccount("100.00", "0.00");

            assertThatThrownBy(() -> account.assertCurrency("USD"))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("held in INR");
        }
    }
}
