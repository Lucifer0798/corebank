package com.corebank.account.domain;

import com.corebank.customer.domain.Customer;
import com.corebank.customer.domain.CustomerStatus;
import com.corebank.customer.domain.KycStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corebank.common.exception.BusinessRuleException;
import com.corebank.common.exception.InsufficientFundsException;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic holds add to an account, in isolation.
 *
 * <p>Two things are worth pinning here above all: that holds <em>compound</em> (a second
 * authorisation sees the first one's money as already gone), and that an ordinary withdrawal
 * cannot spend held money. Miss either and holds still look right on the balance endpoint while
 * guaranteeing nothing -- the failure mode is a posting refused for a purchase the bank had
 * already promised to honour.
 */
class AccountHoldsTest {

    private static Account account(String balance, String overdraft) {
        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setAccountNumber("100100000001");
        account.setAccountClass(AccountClass.CUSTOMER);
        account.setAccountType(AccountType.CURRENT);
        account.setNormalBalance(EntryDirection.CREDIT);
        account.setCurrency("INR");
        account.setBalance(new BigDecimal(balance));
        account.setOverdraftLimit(new BigDecimal(overdraft));
        account.setStatus(AccountStatus.ACTIVE);
        // Every customer account has a customer (V1's ck on account_class), and money leaving it
        // asks whether they are still KYC-verified -- so the fixture needs one that is.
        Customer owner = new Customer();
        owner.setStatus(CustomerStatus.ACTIVE);
        owner.setKycStatus(KycStatus.VERIFIED);
        account.setCustomer(owner);
        return account;
    }

    @Test
    @DisplayName("a hold reduces what is available without touching the balance")
    void aHoldReducesAvailableOnly() {
        Account account = account("1000.00", "0.00");

        account.placeHold(new BigDecimal("400.00"));

        assertThat(account.getBalance())
                .describedAs("nothing has moved -- the customer still owns it and the bank still owes it")
                .isEqualByComparingTo("1000.00");
        assertThat(account.availableBalance()).isEqualByComparingTo("600.00");
        assertThat(account.getHeldAmount()).isEqualByComparingTo("400.00");
    }

    @Test
    @DisplayName("holds compound rather than each seeing the full balance")
    void holdsCompound() {
        // The whole point. Two authorisations for 60 against 100 must not both succeed, or the
        // account goes short the moment they are captured.
        Account account = account("100.00", "0.00");

        account.placeHold(new BigDecimal("60.00"));

        assertThatThrownBy(() -> account.placeHold(new BigDecimal("60.00")))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(account.getHeldAmount()).isEqualByComparingTo("60.00");
    }

    @Test
    @DisplayName("an ordinary withdrawal cannot spend held money")
    void aWithdrawalCannotSpendHeldMoney() {
        // Without the held term in applyEntry's guard, holds would be decorative: the balance
        // endpoint would show 600 available while a 1000 withdrawal still went through.
        Account account = account("1000.00", "0.00");
        account.placeHold(new BigDecimal("400.00"));

        assertThatThrownBy(() -> account.applyEntry(EntryDirection.DEBIT, new BigDecimal("700.00")))
                .isInstanceOf(InsufficientFundsException.class);

        assertThatCode(() -> account.applyEntry(EntryDirection.DEBIT, new BigDecimal("600.00")))
                .describedAs("exactly the unheld remainder is still spendable")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an agreed overdraft can be held against")
    void anOverdraftIsHoldable() {
        Account account = account("100.00", "500.00");

        assertThat(account.availableBalance()).isEqualByComparingTo("600.00");
        assertThatCode(() -> account.placeHold(new BigDecimal("550.00"))).doesNotThrowAnyException();
        assertThat(account.availableBalance()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("freeing a hold gives the money back")
    void freeingRestoresAvailable() {
        Account account = account("1000.00", "0.00");
        account.placeHold(new BigDecimal("400.00"));

        account.freeHold(new BigDecimal("400.00"));

        assertThat(account.getHeldAmount()).isEqualByComparingTo("0.00");
        assertThat(account.availableBalance()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("freeing the same hold twice fails loudly rather than inflating the balance")
    void freeingTwiceIsRefused() {
        // A negative held total would silently overstate the available balance from then on, and
        // nothing downstream would ever notice. Better to break here than to carry it forward.
        Account account = account("1000.00", "0.00");
        account.placeHold(new BigDecimal("400.00"));
        account.freeHold(new BigDecimal("400.00"));

        assertThatThrownBy(() -> account.freeHold(new BigDecimal("400.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("below zero held");
    }

    @Test
    @DisplayName("a frozen account cannot take a new hold")
    void aFrozenAccountRefusesHolds() {
        Account account = account("1000.00", "0.00");
        account.setStatus(AccountStatus.FROZEN);

        assertThatThrownBy(() -> account.placeHold(new BigDecimal("10.00")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("frozen");
    }

    @Test
    @DisplayName("a reversal still overdraws past held money")
    void aReversalStillBypassesTheGuard() {
        // The bypass added for reversals has to keep working now that the guard has a third term,
        // or a correction would become refusable again the moment an unrelated hold existed.
        Account account = account("100.00", "0.00");
        account.placeHold(new BigDecimal("100.00"));

        account.applyEntry(EntryDirection.DEBIT, new BigDecimal("500.00"), true);

        assertThat(account.getBalance()).isEqualByComparingTo("-400.00");
    }
}
