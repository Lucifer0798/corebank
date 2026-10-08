package com.corebank.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corebank.account.domain.AccountType;
import com.corebank.account.dto.CaptureHoldRequest;
import com.corebank.account.dto.HoldResponse;
import com.corebank.account.dto.OpenAccountRequest;
import com.corebank.account.dto.PlaceHoldRequest;
import com.corebank.common.exception.LimitExceededException;
import com.corebank.customer.domain.KycStatus;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.service.CustomerService;
import com.corebank.transaction.dto.AmountRequest;
import com.corebank.transaction.service.TransactionService;
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
 * That capturing a hold is exempt from the daily velocity limit, and that placing one is not.
 *
 * <p>Its own class, and its own Spring context, for a reason worth recording. The obvious place for
 * this was {@code VelocityLimitsTest}, and a test there did pass -- but it passed just as happily
 * with the exemption removed, because the suite-wide limits are enormous and a small capture is
 * under them either way. A test that cannot fail is worse than no test, since it reads as coverage.
 *
 * <p>Making it real needs limits small enough for a capture to breach, which needs its own
 * property source. The extra context is the price of the assertion meaning something.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "corebank.limits.daily-debit-limit=1000.00",
        "corebank.limits.single-transaction-limit=1000.00",
})
class HoldCaptureLimitExemptionTest {

    @Autowired
    private HoldService holdService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private TransactionService transactionService;

    private UUID accountId;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString();
        UUID customerId = customerService.create(new CreateCustomerRequest(
                "Leena", "Varma", "leena.varma." + unique + "@example.com", null,
                LocalDate.of(1991, 9, 30))).id();
        customerService.updateKyc(customerId, KycStatus.VERIFIED, "Test fixture", com.corebank.config.TestDeciders.STAFF);

        accountId = accountService.open(new OpenAccountRequest(
                customerId, AccountType.SAVINGS, "INR", BigDecimal.ZERO)).id();
        // Funded well past the daily limit on purpose: the refusals below have to be the limit
        // talking, not a shortage of money.
        transactionService.deposit(accountId,
                new AmountRequest(new BigDecimal("5000.00"), "INR", "Funding"), "hcl-fund-" + unique);
    }

    @Test
    @DisplayName("a capture goes through even once the day's limit is spent")
    void captureIsExemptOnceTheLimitIsSpent() {
        // Authorise 600 of the 1000 allowance...
        HoldResponse hold = holdService.place(accountId,
                new PlaceHoldRequest(new BigDecimal("600.00"), "INR", "Hotel", null));

        // ...then genuinely spend 600 more, bringing settled debits to 600 with 600 still held.
        transactionService.withdraw(accountId,
                new AmountRequest(new BigDecimal("600.00"), "INR", "ATM"), "hcl-atm");

        // Capturing now would take the day's debits to 1200 against a limit of 1000. It must still
        // succeed: the hold guaranteed this money, and a guarantee that a later limit can revoke is
        // not a guarantee. Remove the exemption in HoldService.capture and this is the test that
        // goes red.
        assertThatCode(() -> holdService.capture(hold.reference(), new CaptureHoldRequest(null)))
                .doesNotThrowAnyException();

        assertThat(accountService.get(accountId).balance()).isEqualByComparingTo("3800.00");
    }

    @Test
    @DisplayName("placing a hold is refused once the day's limit is spent")
    void placingIsNotExempt() {
        // The other half, and the reason the exemption is safe: the check happens at authorisation,
        // so holds are not a way round the ceiling -- they are simply checked earlier.
        transactionService.withdraw(accountId,
                new AmountRequest(new BigDecimal("900.00"), "INR", "ATM"), "hcl-atm-2");

        assertThatThrownBy(() -> holdService.place(accountId,
                new PlaceHoldRequest(new BigDecimal("200.00"), "INR", "Hotel", null)))
                .isInstanceOf(LimitExceededException.class)
                .extracting(ex -> ((LimitExceededException) ex).getCode())
                .isEqualTo("DAILY_LIMIT_EXCEEDED");
    }

    @Test
    @DisplayName("two holds that together breach the limit are refused on the second")
    void outstandingHoldsCompoundAtAuthorisation() {
        holdService.place(accountId, new PlaceHoldRequest(new BigDecimal("600.00"), "INR", "First", null));

        // Without counting today's outstanding holds, this second authorisation would see zero
        // settled debits and be allowed -- and both captures would then land, spending 1200.
        assertThatThrownBy(() -> holdService.place(accountId,
                new PlaceHoldRequest(new BigDecimal("600.00"), "INR", "Second", null)))
                .isInstanceOf(LimitExceededException.class)
                .extracting(ex -> ((LimitExceededException) ex).getCode())
                .isEqualTo("DAILY_LIMIT_EXCEEDED");
    }
}
