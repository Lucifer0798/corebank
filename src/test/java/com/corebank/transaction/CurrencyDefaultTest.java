package com.corebank.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corebank.account.domain.AccountType;
import com.corebank.account.dto.OpenAccountRequest;
import com.corebank.account.dto.PlaceHoldRequest;
import com.corebank.account.service.AccountService;
import com.corebank.account.service.HoldService;
import com.corebank.common.exception.BusinessRuleException;
import com.corebank.config.TestDeciders;
import com.corebank.customer.domain.KycStatus;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.service.CustomerService;
import com.corebank.schedule.domain.ScheduleFrequency;
import com.corebank.schedule.dto.CreateScheduledTransferRequest;
import com.corebank.schedule.service.ScheduledTransferService;
import com.corebank.transaction.dto.AmountRequest;
import com.corebank.transaction.dto.TransactionResponse;
import com.corebank.transaction.dto.TransferRequest;
import com.corebank.transaction.service.TransactionService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * A request that names no currency is in its account's own. It used to mean rupees, so on any
 * other account every such request was refused -- "held in USD, not INR" -- which is exactly what
 * the frontend sent until #50, and what any other client still would.
 */
@SpringBootTest
class CurrencyDefaultTest {

    @Autowired private CustomerService customerService;
    @Autowired private AccountService accountService;
    @Autowired private TransactionService transactionService;
    @Autowired private HoldService holdService;
    @Autowired private ScheduledTransferService scheduledTransfers;

    private UUID dollars;
    private UUID moreDollars;
    private UUID rupees;

    @BeforeEach
    void setUp() {
        UUID customerId = customerService.create(new CreateCustomerRequest("Neha", "Kapoor",
                "neha." + UUID.randomUUID() + "@example.com", null, LocalDate.of(1990, 1, 1))).id();
        customerService.updateKyc(customerId, KycStatus.VERIFIED, null, TestDeciders.STAFF);
        dollars = open(customerId, "USD");
        moreDollars = open(customerId, "USD");
        rupees = open(customerId, "INR");
        transactionService.deposit(dollars, new AmountRequest(new BigDecimal("500.00"), null, "Fund"), key());
    }

    private UUID open(UUID customerId, String currency) {
        return accountService.open(new OpenAccountRequest(customerId, AccountType.SAVINGS, currency, BigDecimal.ZERO)).id();
    }

    private static String key() {
        return "cur-" + UUID.randomUUID();
    }

    @Test
    @DisplayName("a deposit and a withdrawal with no currency are in the account's own")
    void depositAndWithdrawalDefaultToTheAccount() {
        // setUp's deposit already named no currency; it landed.
        assertThat(accountService.get(dollars).balance()).isEqualByComparingTo("500.00");

        TransactionResponse withdrawal = transactionService.withdraw(dollars,
                new AmountRequest(new BigDecimal("100.00"), null, "Cash"), key());

        assertThat(withdrawal.currency()).isEqualTo("USD");
        assertThat(accountService.get(dollars).balance()).isEqualByComparingTo("400.00");
    }

    @Test
    @DisplayName("a transfer with no currency is in the paying account's, converting when the other differs")
    void aTransferDefaultsToThePayer() {
        TransactionResponse sameCurrency = transactionService.transfer(
                new TransferRequest(dollars, moreDollars, new BigDecimal("50.00"), null, "Between dollar accounts"), key());
        assertThat(sameCurrency.currency()).isEqualTo("USD");
        assertThat(sameCurrency.fx()).isNull();

        TransactionResponse crossCurrency = transactionService.transfer(
                new TransferRequest(dollars, rupees, new BigDecimal("50.00"), null, "To rupees"), key());
        assertThat(crossCurrency.currency()).isEqualTo("USD");
        assertThat(crossCurrency.fx()).isNotNull();
        assertThat(crossCurrency.fx().counterCurrency()).isEqualTo("INR");
    }

    @Test
    @DisplayName("a hold and a standing order with no currency are in the account's own")
    void holdsAndStandingOrdersDefaultToTheAccount() {
        assertThat(holdService.place(dollars, new PlaceHoldRequest(new BigDecimal("20.00"), null, "Hotel", 24)).currency())
                .isEqualTo("USD");

        assertThat(scheduledTransfers.create(new CreateScheduledTransferRequest(dollars, moreDollars,
                new BigDecimal("10.00"), null, "Allowance", ScheduleFrequency.MONTHLY,
                LocalDate.now(ZoneOffset.UTC), null)).currency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("a currency that is named and wrong is still refused")
    void aWrongCurrencyIsStillRefused() {
        // Only the omitted case changed. Naming rupees for a dollar account is a mistake, and saying
        // so is better than quietly posting it in dollars.
        assertThatThrownBy(() -> transactionService.deposit(dollars,
                new AmountRequest(new BigDecimal("10.00"), "INR", "Wrong"), key()))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getCode())
                .isEqualTo("CURRENCY_MISMATCH");
        assertThat(accountService.get(dollars).balance()).isEqualByComparingTo("500.00");
    }
}
