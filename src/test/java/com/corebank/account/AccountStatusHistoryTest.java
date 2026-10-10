package com.corebank.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebank.account.domain.AccountStatus;
import com.corebank.account.domain.AccountStatusChange;
import com.corebank.account.domain.AccountType;
import com.corebank.account.dto.OpenAccountRequest;
import com.corebank.account.repository.AccountStatusChangeRepository;
import com.corebank.account.service.AccountService;
import com.corebank.common.security.Actor;
import com.corebank.config.TestDeciders;
import com.corebank.customer.domain.KycStatus;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.service.CustomerService;
import com.corebank.transaction.dto.AmountRequest;
import com.corebank.transaction.service.TransactionService;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.repository.CrudRepository;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Every freeze, unfreeze and closure is kept, with who did it and why. A freeze stops all money
 * movement, usually on a fraud report or a legal order; it has to be possible to say who did it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountStatusHistoryTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private AccountStatusChangeRepository changes;

    private UUID accountId;

    @BeforeEach
    void setUp() {
        UUID customerId = customerService.create(new CreateCustomerRequest("Nikhil", "Rao",
                "nikhil." + UUID.randomUUID() + "@example.com", null, LocalDate.of(1990, 1, 1))).id();
        customerService.updateKyc(customerId, KycStatus.VERIFIED, null, TestDeciders.STAFF);
        accountId = accountService.open(new OpenAccountRequest(customerId, AccountType.SAVINGS, "INR", BigDecimal.ZERO)).id();
    }

    private static RequestPostProcessor teller() {
        return jwt().jwt(token -> token.subject("teller-meena-subject").claim("preferred_username", "meena"))
                .authorities(new SimpleGrantedAuthority("ROLE_TELLER"));
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject("admin-priya-subject").claim("preferred_username", "priya"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private ResultActions change(String action, RequestPostProcessor who, String body) throws Exception {
        var request = post("/api/v1/accounts/{id}/" + action, accountId).with(who);
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request);
    }

    private ResultActions history() throws Exception {
        return mockMvc.perform(get("/api/v1/accounts/{id}/status-changes", accountId).with(teller()));
    }

    @Test
    @DisplayName("each freeze, unfreeze and closure is kept with who did it and why -- newest first")
    void changesAreRecorded() throws Exception {
        change("freeze", teller(), "{\"reason\":\"Customer reported the card stolen\"}").andExpect(status().isOk());
        change("unfreeze", teller(), null).andExpect(status().isOk());
        change("close", admin(), "{\"reason\":\"Customer moving abroad\"}").andExpect(status().isOk());

        history().andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].fromStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.content[0].toStatus").value("CLOSED"))
                .andExpect(jsonPath("$.content[0].changedByName").value("priya"))
                .andExpect(jsonPath("$.content[0].reason").value("Customer moving abroad"))
                .andExpect(jsonPath("$.content[1].fromStatus").value("FROZEN"))
                .andExpect(jsonPath("$.content[1].toStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.content[1].reason").doesNotExist())
                .andExpect(jsonPath("$.content[2].toStatus").value("FROZEN"))
                .andExpect(jsonPath("$.content[2].changedBySubject").value("teller-meena-subject"))
                .andExpect(jsonPath("$.content[2].changedByName").value("meena"))
                .andExpect(jsonPath("$.content[2].reason").value("Customer reported the card stolen"));
    }

    @Test
    @DisplayName("freezing or closing without saying why is refused, and changes nothing")
    void freezingAndClosingNeedAReason() throws Exception {
        change("freeze", teller(), null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("STATUS_REASON_REQUIRED"));
        change("close", admin(), "{\"reason\":\"   \"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("STATUS_REASON_REQUIRED"));

        assertThat(accountService.get(accountId).status()).isEqualTo(AccountStatus.ACTIVE);
        history().andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("a change to the status the account already has is refused, not recorded as a non-event")
    void aNoOpChangeIsRefused() throws Exception {
        change("unfreeze", teller(), null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("STATUS_UNCHANGED"));

        history().andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("a closure refused for another reason leaves no history behind")
    void aRefusedClosureRecordsNothing() throws Exception {
        // The history row is written only once every check has passed; a closure turned away for the
        // balance must not read, later, as though it happened.
        transactionService.deposit(accountId, new AmountRequest(new BigDecimal("10.00"), "INR", "Fund"),
                "status-fund-" + UUID.randomUUID());

        change("close", admin(), "{\"reason\":\"Customer moving abroad\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BALANCE_NOT_ZERO"));

        history().andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("staff can read the history; a customer cannot")
    void historyIsStaffOnly() throws Exception {
        // The reason for a freeze can be a fraud report or a legal order -- not the account holder's to read.
        mockMvc.perform(get("/api/v1/accounts/{id}/status-changes", accountId)
                        .with(jwt().jwt(token -> token.subject("some-customer"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the history cannot be edited: the repository offers no way to delete or update")
    void theHistoryIsAppendOnly() {
        assertThat(CrudRepository.class.isAssignableFrom(AccountStatusChangeRepository.class)).isFalse();
        assertThat(Arrays.stream(AccountStatusChangeRepository.class.getMethods()).map(Method::getName))
                .noneMatch(name -> name.startsWith("delete") || name.startsWith("update")
                        || name.startsWith("remove"));
    }

    @Test
    @DisplayName("the database refuses a freeze with no reason, whoever writes it")
    void theSchemaBacksUpTheReasonRule() {
        AccountStatusChange unexplained = new AccountStatusChange(accountId, AccountStatus.ACTIVE,
                AccountStatus.FROZEN, new Actor("someone", "someone"), null, Instant.now());

        assertThatThrownBy(() -> changes.save(unexplained)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
