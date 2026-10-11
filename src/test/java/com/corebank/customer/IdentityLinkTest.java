package com.corebank.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebank.common.security.Actor;
import com.corebank.customer.domain.IdentityLink;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.repository.CustomerRepository;
import com.corebank.customer.repository.IdentityLinkRepository;
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
import org.springframework.data.repository.CrudRepository;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Which login can read a customer's money. A link used to be silently replaceable by a second one:
 * the record pointed at someone else's login, the customer's own stopped resolving, and nothing
 * said who had done it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class IdentityLinkTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private CustomerRepository customers;

    @Autowired
    private IdentityLinkRepository links;

    private UUID customerId;
    private String original;
    private String replacement;

    @BeforeEach
    void setUp() {
        customerId = customerService.create(new CreateCustomerRequest("Lata", "Iyer",
                "lata." + UUID.randomUUID() + "@example.com", null, LocalDate.of(1990, 1, 1))).id();
        original = "original-" + UUID.randomUUID();
        replacement = "replacement-" + UUID.randomUUID();
    }

    private static RequestPostProcessor teller() {
        return jwt().jwt(token -> token.subject("teller-kavya-subject").claim("preferred_username", "kavya"))
                .authorities(new SimpleGrantedAuthority("ROLE_TELLER"));
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject("admin-priya-subject").claim("preferred_username", "priya"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static RequestPostProcessor customer(String subject) {
        return jwt().jwt(token -> token.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private ResultActions link(RequestPostProcessor who, String subject) throws Exception {
        return mockMvc.perform(patch("/api/v1/customers/{id}/identity", customerId).with(who)
                .contentType(MediaType.APPLICATION_JSON).content("{\"keycloakSubject\":\"" + subject + "\"}"));
    }

    private ResultActions relink(RequestPostProcessor who, String body) throws Exception {
        return mockMvc.perform(put("/api/v1/customers/{id}/identity", customerId).with(who)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String linkedSubject() {
        return customers.findById(customerId).orElseThrow().getKeycloakSubject();
    }

    @Test
    @DisplayName("a second link is refused, and the first one stays")
    void aLinkCannotBeSilentlyReplaced() throws Exception {
        link(teller(), original).andExpect(status().isOk());

        link(teller(), replacement)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CUSTOMER_ALREADY_LINKED"));

        assertThat(linkedSubject()).isEqualTo(original);
    }

    @Test
    @DisplayName("an admin relink moves access from the old login to the new one, and both are kept")
    void anAdminRelinkIsRecorded() throws Exception {
        link(teller(), original).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/customers/me").with(customer(original))).andExpect(status().isOk());

        relink(admin(), "{\"keycloakSubject\":\"" + replacement + "\",\"reason\":\"Keycloak account recreated\"}")
                .andExpect(status().isOk());

        // The effect that matters: the old login no longer reaches this customer, the new one does.
        mockMvc.perform(get("/api/v1/customers/me").with(customer(original))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/customers/me").with(customer(replacement)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(customerId.toString()));

        mockMvc.perform(get("/api/v1/customers/{id}/identity-links", customerId).with(teller()))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].previousSubject").value(original))
                .andExpect(jsonPath("$.content[0].newSubject").value(replacement))
                .andExpect(jsonPath("$.content[0].linkedByName").value("priya"))
                .andExpect(jsonPath("$.content[0].reason").value("Keycloak account recreated"))
                .andExpect(jsonPath("$.content[1].previousSubject").doesNotExist())
                .andExpect(jsonPath("$.content[1].newSubject").value(original))
                .andExpect(jsonPath("$.content[1].linkedBySubject").value("teller-kavya-subject"));
    }

    @Test
    @DisplayName("a relink needs a reason, and only an admin can make one")
    void aRelinkIsAnAdminDecisionWithAReason() throws Exception {
        link(teller(), original).andExpect(status().isOk());

        relink(admin(), "{\"keycloakSubject\":\"" + replacement + "\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RELINK_REASON_REQUIRED"));
        relink(admin(), "{\"keycloakSubject\":\"" + replacement + "\",\"reason\":\"  \"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RELINK_REASON_REQUIRED"));
        relink(teller(), "{\"keycloakSubject\":\"" + replacement + "\",\"reason\":\"Because\"}")
                .andExpect(status().isForbidden());

        assertThat(linkedSubject()).isEqualTo(original);
    }

    @Test
    @DisplayName("a relink still cannot take a login that belongs to another customer")
    void aRelinkCannotStealAnotherCustomersLogin() throws Exception {
        UUID other = customerService.create(new CreateCustomerRequest("Arun", "Iyer",
                "arun." + UUID.randomUUID() + "@example.com", null, LocalDate.of(1990, 1, 1))).id();
        customerService.linkIdentity(other, replacement);
        link(teller(), original).andExpect(status().isOk());

        relink(admin(), "{\"keycloakSubject\":\"" + replacement + "\",\"reason\":\"Typo\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDENTITY_ALREADY_LINKED"));
        assertThat(linkedSubject()).isEqualTo(original);
    }

    @Test
    @DisplayName("relinking an unlinked customer, or to the same login, is refused")
    void meaninglessRelinksAreRefused() throws Exception {
        relink(admin(), "{\"keycloakSubject\":\"" + original + "\",\"reason\":\"x\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_LINKED"));

        link(teller(), original).andExpect(status().isOk());
        relink(admin(), "{\"keycloakSubject\":\"" + original + "\",\"reason\":\"x\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDENTITY_UNCHANGED"));
    }

    @Test
    @DisplayName("a customer cannot read the identity history")
    void historyIsStaffOnly() throws Exception {
        mockMvc.perform(get("/api/v1/customers/{id}/identity-links", customerId).with(customer("anyone")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the history cannot be edited, and the schema refuses an unexplained relink")
    void theHistoryIsAppendOnlyAndBackedByTheSchema() {
        assertThat(CrudRepository.class.isAssignableFrom(IdentityLinkRepository.class)).isFalse();
        assertThat(Arrays.stream(IdentityLinkRepository.class.getMethods()).map(Method::getName))
                .noneMatch(name -> name.startsWith("delete") || name.startsWith("update") || name.startsWith("remove"));

        IdentityLink unexplained = new IdentityLink(customerId, "before", "after", new Actor("someone", null), null,
                Instant.now());
        assertThatThrownBy(() -> links.save(unexplained)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
