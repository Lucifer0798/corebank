package com.corebank.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corebank.account.domain.AccountType;
import com.corebank.account.dto.OpenAccountRequest;
import com.corebank.account.service.AccountService;
import com.corebank.common.exception.LimitExceededException;
import com.corebank.customer.domain.KycStatus;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.service.CustomerService;
import com.corebank.transaction.dto.AmountRequest;
import com.corebank.transaction.dto.TransferRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Velocity limits on accounts held in a currency other than the one the limits are stated in.
 *
 * <p>#34 wrote the limits when every account was in rupees, as a bare number with no currency.
 * #36 made dollar, euro and sterling accounts real. Together they compared a dollar amount against
 * a rupee limit as though the two were the same unit, so a dollar account got roughly 83 times the
 * allowance of a rupee one and a sterling account about 105 times.
 *
 * <p>The dollar account here is funded through an FX transfer rather than a cash deposit on
 * purpose: this test has to fail on the limit and on nothing else, and a dollar deposit was broken
 * by a separate bug in the same seam.
 */
@SpringBootTest
@TestPropertySource(properties = {
        // Both limits stated in rupees. A dollar is about 83 rupees at the seeded mid rate.
        "corebank.limits.daily-debit-limit=1000.00",
        "corebank.limits.single-transaction-limit=1000.00",
})
class CurrencyAwareLimitsTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private CustomerService customerService;

    private UUID dollarAccount;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString();
        UUID customerId = customerService.create(new CreateCustomerRequest(
                "Reza", "Ahmadi", "reza.ahmadi." + unique + "@example.com", null,
                LocalDate.of(1986, 3, 9))).id();
        customerService.updateKyc(customerId, KycStatus.VERIFIED);

        UUID rupeeAccount = accountService.open(new OpenAccountRequest(
                customerId, AccountType.SAVINGS, "INR", BigDecimal.ZERO)).id();
        dollarAccount = accountService.open(new OpenAccountRequest(
                customerId, AccountType.SAVINGS, "USD", BigDecimal.ZERO)).id();

        transactionService.deposit(rupeeAccount,
                new AmountRequest(new BigDecimal("5000.00"), "INR", "Funding"), "cal-fund-" + unique);
        // 1000 rupees, exactly at the ceiling, buys 11.94 dollars.
        transactionService.transfer(new TransferRequest(rupeeAccount, dollarAccount,
                new BigDecimal("1000.00"), "INR", "To USD"), "cal-fx-" + unique);
    }

    @Test
    @DisplayName("a dollar withdrawal is measured against the limit in rupees")
    void aDollarAmountIsConvertedBeforeItIsChecked() {
        // 13 dollars is about 1079 rupees, over the 1000-rupee ceiling. Read as a bare number, 13 is
        // nowhere near 1000 and the limit would wave it through -- which is exactly what happened:
        // the posting then failed on insufficient funds instead, a different refusal for a
        // different reason, and the limit had never applied at all.
        assertThatThrownBy(() -> transactionService.withdraw(dollarAccount,
                new AmountRequest(new BigDecimal("13.00"), "USD", "ATM"), "cal-wd-over"))
                .isInstanceOf(LimitExceededException.class)
                .extracting(ex -> ((LimitExceededException) ex).getCode())
                .isEqualTo("TRANSACTION_LIMIT_EXCEEDED");
    }

    @Test
    @DisplayName("a dollar withdrawal under the limit in rupees still goes through")
    void aDollarAmountUnderTheLimitPasses() {
        // 10 dollars is about 830 rupees -- under the ceiling once converted, so it must be allowed.
        // Without this the fix could pass the test above simply by refusing every dollar withdrawal.
        assertThatCode(() -> transactionService.withdraw(dollarAccount,
                new AmountRequest(new BigDecimal("10.00"), "USD", "ATM"), "cal-wd-under"))
                .doesNotThrowAnyException();
        assertThat(accountService.get(dollarAccount).balance()).isEqualByComparingTo("1.94");
    }

    @Test
    @DisplayName("the refusal states the amounts in the currency the limit is in")
    void theRefusalIsInTheLimitsCurrency() {
        // Telling someone "13.00 was requested against a limit of 1000.00" would read as nonsense --
        // the two numbers are in different units. The message has to compare like with like.
        assertThatThrownBy(() -> transactionService.withdraw(dollarAccount,
                new AmountRequest(new BigDecimal("13.00"), "USD", "ATM"), "cal-wd-msg"))
                .hasMessageContaining("INR");
    }
}
