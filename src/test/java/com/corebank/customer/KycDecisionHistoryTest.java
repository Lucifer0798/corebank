package com.corebank.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebank.customer.domain.KycDecider;
import com.corebank.customer.domain.KycDecision;
import com.corebank.customer.domain.KycStatus;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.repository.KycDecisionRepository;
import com.corebank.customer.service.CustomerService;
import java.lang.reflect.Method;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.repository.CrudRepository;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Every KYC decision is kept, with who made it and why. A lapsed KYC stops money leaving the
 * customer's accounts, so "who rejected this customer, and on what grounds" has to have an answer.
 */
@SpringBootTest
@AutoConfigureMockMvc
class KycDecisionHistoryTest {

    private static final String ADMIN_SUBJECT = "kyc-admin-subject";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private KycDecisionRepository decisions;

    private UUID customerId;

    @BeforeEach
    void setUp() {
        customerId = customerService.create(new CreateCustomerRequest("Tara", "Joshi",
                "tara." + UUID.randomUUID() + "@example.com", null, LocalDate.of(1990, 1, 1))).id();
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject(ADMIN_SUBJECT).claim("preferred_username", "priya"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static RequestPostProcessor teller() {
        return jwt().jwt(token -> token.subject("kyc-teller-subject"))
                .authorities(new SimpleGrantedAuthority("ROLE_TELLER"));
    }

    private ResultActions decide(String body) throws Exception {
        return mockMvc.perform(patch("/api/v1/customers/{id}/kyc", customerId).with(admin())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions history() throws Exception {
        return mockMvc.perform(get("/api/v1/customers/{id}/kyc-decisions", customerId).with(teller()));
    }

    @Test
    @DisplayName("each decision is kept with who made it, from what, to what, and why -- newest first")
    void decisionsAreRecorded() throws Exception {
        decide("{\"kycStatus\":\"VERIFIED\"}").andExpect(status().isOk());
        decide("{\"kycStatus\":\"REJECTED\",\"reason\":\"Address could not be confirmed on re-check\"}")
                .andExpect(status().isOk());

        history().andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].fromStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.content[0].toStatus").value("REJECTED"))
                .andExpect(jsonPath("$.content[0].reason").value("Address could not be confirmed on re-check"))
                // From the token, never the body: there is nothing in the request to claim to be someone else.
                .andExpect(jsonPath("$.content[0].decidedBySubject").value(ADMIN_SUBJECT))
                .andExpect(jsonPath("$.content[0].decidedByName").value("priya"))
                .andExpect(jsonPath("$.content[1].fromStatus").value("PENDING"))
                .andExpect(jsonPath("$.content[1].toStatus").value("VERIFIED"));
    }

    @Test
    @DisplayName("restricting a customer without saying why is refused, and changes nothing")
    void aRestrictionNeedsAReason() throws Exception {
        decide("{\"kycStatus\":\"VERIFIED\"}").andExpect(status().isOk());

        decide("{\"kycStatus\":\"REJECTED\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("KYC_REASON_REQUIRED"));
        // Whitespace is not a reason.
        decide("{\"kycStatus\":\"PENDING\",\"reason\":\"   \"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("KYC_REASON_REQUIRED"));

        assertThat(customerService.get(customerId).kycStatus())
                .describedAs("the refused decisions must not have applied")
                .isEqualTo(KycStatus.VERIFIED);
        history().andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("staff can read the history; a customer cannot")
    void historyIsStaffOnly() throws Exception {
        mockMvc.perform(get("/api/v1/customers/{id}/kyc-decisions", customerId)
                        .with(jwt().jwt(token -> token.subject("some-customer"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the history cannot be edited: the repository offers no way to delete or update")
    void theHistoryIsAppendOnly() {
        // Structural rather than behavioural, and deliberately so: the guarantee is that no code
        // path exists. Moving the repository to JpaRepository or CrudRepository, or declaring a
        // delete or modifying query on it, fails here.
        assertThat(CrudRepository.class.isAssignableFrom(KycDecisionRepository.class)).isFalse();
        assertThat(Arrays.stream(KycDecisionRepository.class.getMethods()).map(Method::getName))
                .noneMatch(name -> name.startsWith("delete") || name.startsWith("update")
                        || name.startsWith("remove"));
    }

    @Test
    @DisplayName("the database refuses a restricting decision with no reason, whoever writes it")
    void theSchemaBacksUpTheReasonRule() {
        KycDecision unexplained = new KycDecision(customerId, KycStatus.VERIFIED, KycStatus.REJECTED,
                new KycDecider("someone", "someone"), null, Instant.now());

        assertThatThrownBy(() -> {
            decisions.save(unexplained);
            decisions.findByCustomerIdOrderByDecidedAtDesc(customerId, PageRequest.of(0, 1)).getContent();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }
}
