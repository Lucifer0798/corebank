package com.corebank.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebank.account.domain.AccountStatus;
import com.corebank.account.domain.AccountType;
import com.corebank.account.dto.OpenAccountRequest;
import com.corebank.account.repository.AccountRepository;
import com.corebank.account.service.AccountService;
import com.corebank.account.service.InterestService;
import com.corebank.common.exception.BusinessRuleException;
import com.corebank.config.TestDeciders;
import com.corebank.customer.domain.KycStatus;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.service.CustomerService;
import com.corebank.transaction.dto.AmountRequest;
import com.corebank.transaction.service.TransactionService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Interest owed on an account that is being closed. Closure used to check only the balance, so an
 * account emptied to zero closed with its accrued interest still on it -- and the next monthly run
 * credited that interest into the closed account, where nothing could ever withdraw it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ClosedAccountInterestTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private CustomerService customerService;
    @Autowired private AccountService accountService;
    @Autowired private TransactionService transactionService;
    @Autowired private InterestService interestService;
    @Autowired private AccountRepository accounts;
    @Autowired private PlatformTransactionManager transactionManager;

    private UUID accountId;

    @BeforeEach
    void setUp() {
        UUID customerId = customerService.create(new CreateCustomerRequest("Kavya", "Menon",
                "kavya." + UUID.randomUUID() + "@example.com", null, LocalDate.of(1990, 1, 1))).id();
        customerService.updateKyc(customerId, KycStatus.VERIFIED, null, TestDeciders.STAFF);
        accountId = accountService.open(new OpenAccountRequest(customerId, AccountType.SAVINGS, "INR", BigDecimal.ZERO)).id();
        transactionService.deposit(accountId, new AmountRequest(new BigDecimal("10000.00"), "INR", "Funding"), key());
        // Twenty days at 3.5% on 10,000: 19.1780 accrued, of which 19.17 is payable in whole paise.
        LocalDate day = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 20; i++) {
            interestService.accrue(accountId, day.plusDays(i));
        }
    }

    private static String key() {
        return "cai-" + UUID.randomUUID();
    }

    private void withdraw(String amount) {
        transactionService.withdraw(accountId, new AmountRequest(new BigDecimal(amount), "INR", "Cash"), key());
    }

    private void close() {
        accountService.changeStatus(accountId, AccountStatus.CLOSED, "Customer request");
    }

    private static String codeOf(Throwable ex) {
        return ((BusinessRuleException) ex).getCode();
    }

    private static RequestPostProcessor teller() {
        return jwt().jwt(token -> token.subject("teller-anil-subject").claim("preferred_username", "anil"))
                .authorities(new SimpleGrantedAuthority("ROLE_TELLER"));
    }

    private ResultActions payOut(RequestPostProcessor who) throws Exception {
        return mockMvc.perform(post("/api/v1/accounts/{id}/interest/pay-out", accountId).with(who));
    }

    @Test
    @DisplayName("an account emptied to zero does not close while interest is still owed on it")
    void unpaidInterestBlocksClosure() {
        withdraw("10000.00");

        assertThatThrownBy(this::close)
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ClosedAccountInterestTest::codeOf).isEqualTo("CLOSURE_BLOCKED");
        assertThatThrownBy(this::close).hasMessageContaining("19.17 INR of interest not yet paid out");
        assertThat(accountService.require(accountId).getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("with money still in it, the refusal names the interest before the balance")
    void obligationsAreReportedBeforeTheBalance() {
        // Paying the interest out adds to the balance, so the order that works is: pay out, then
        // empty the account once. Reporting the balance first sent staff round twice.
        assertThatThrownBy(this::close)
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ClosedAccountInterestTest::codeOf).isEqualTo("CLOSURE_BLOCKED");
    }

    @Test
    @DisplayName("staff can pay the interest out now, after which the account empties and closes")
    void payingOutLetsTheAccountClose() throws Exception {
        withdraw("10000.00");

        payOut(teller())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("INTEREST"))
                .andExpect(jsonPath("$.amount").value(19.17));
        assertThat(accountService.require(accountId).getBalance()).isEqualByComparingTo("19.17");
        // The fraction of a paisa cannot be paid in real money, and does not hold the closure up.
        assertThat(accountService.require(accountId).getAccruedInterest()).isEqualByComparingTo("0.0080");

        withdraw("19.17");
        close();
        assertThat(accountService.require(accountId).getStatus()).isEqualTo(AccountStatus.CLOSED);
    }

    @Test
    @DisplayName("paying out with nothing whole to pay is refused, and says why")
    void nothingToPayIsRefused() throws Exception {
        interestService.capitalise(accountId);

        payOut(teller())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_INTEREST_PAYABLE"));
    }

    @Test
    @DisplayName("only staff can pay interest out")
    void payOutIsStaffOnly() throws Exception {
        payOut(jwt().jwt(token -> token.subject("some-customer"))
                        .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                .andExpect(status().isForbidden());
        assertThat(accountService.require(accountId).getAccruedInterest()).isEqualByComparingTo("19.1780");
    }

    @Test
    @DisplayName("an account closed with interest on it is never credited it")
    void aClosedAccountIsNeverPaidInterest() {
        // Accounts closed before the check above still carry what they had accrued. Set one up the
        // way they were left: emptied and closed, accrued interest and all.
        withdraw("10000.00");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var account = accounts.findById(accountId).orElseThrow();
            account.setStatus(AccountStatus.CLOSED);
            account.setClosedAt(Instant.now());
        });

        assertThat(interestService.findAccountsToCapitalise()).doesNotContain(accountId);
        assertThatThrownBy(() -> interestService.capitalise(accountId))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ClosedAccountInterestTest::codeOf).isEqualTo("ACCOUNT_CLOSED");
        assertThat(accountService.require(accountId).getBalance()).isEqualByComparingTo("0.00");
        assertThat(accountService.require(accountId).getAccruedInterest()).isEqualByComparingTo("19.1780");
    }
}
