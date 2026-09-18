package com.corebank.common.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.service.CustomerService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The two database-level failures {@link GlobalExceptionHandler} maps to a 409 -- a constraint
 * violation and a lost optimistic-lock race -- are the ones nothing else here can reach on
 * purpose. Both only ever arise from a genuine race: every unique constraint in the schema that
 * a request can collide with is pre-checked in the service layer first ({@code EMAIL_TAKEN},
 * {@code IDENTITY_ALREADY_LINKED}), and {@code @Version} conflicts need two writers interleaving
 * inside the same row's transaction window. Racing for them in a test would make it flaky, and a
 * flaky test is worse than none, so the exception is injected at the service boundary instead:
 * what's under test is that it reaches the handler and comes back out as a clean problem
 * document, which is exactly the part that was never covered.
 *
 * <p>The leak assertions are the point, not decoration. {@code GlobalExceptionHandler}'s own
 * javadoc promises "internal detail never leaks", and a constraint violation is the most likely
 * exception in this application to carry a raw schema name or SQL fragment in its message.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GlobalExceptionHandlerTest {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String NEW_CUSTOMER = """
            {"firstName":"Race","lastName":"Loser","email":"race@example.com","dateOfBirth":"1990-01-01"}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomerService customerService;

    private static RequestPostProcessor teller() {
        return jwt().jwt(builder -> builder.subject("handler-test-teller"))
                .authorities(new SimpleGrantedAuthority("ROLE_TELLER"));
    }

    @Test
    @DisplayName("a constraint violation becomes a 409 that says nothing about the schema")
    void dataIntegrityViolationBecomesConflict() throws Exception {
        when(customerService.create(any(CreateCustomerRequest.class))).thenThrow(
                new DataIntegrityViolationException(
                        "could not execute statement [ERROR: duplicate key value violates unique "
                                + "constraint \"uk_customer_email\"] [insert into customer ...]"));

        mockMvc.perform(post("/api/v1/customers").with(teller()).contentType(JSON).content(NEW_CUSTOMER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_INTEGRITY_VIOLATION"))
                .andExpect(jsonPath("$.detail").value("The request conflicts with data that already exists"))
                .andExpect(jsonPath("$.type").value("https://corebank.example/problems/data-integrity-violation"))
                .andExpect(jsonPath("$.timestamp").exists())
                // The raw message carried a constraint name and a SQL fragment; neither may survive
                // into the response, or the handler's "internal detail never leaks" claim is false.
                .andExpect(content().string(Matchers.not(Matchers.containsString("uk_customer_email"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("insert into"))));
    }

    @Test
    @DisplayName("a lost optimistic-lock race becomes a 409 telling the caller to retry")
    void optimisticLockFailureBecomesConflict() throws Exception {
        when(customerService.create(any(CreateCustomerRequest.class))).thenThrow(
                new OptimisticLockingFailureException(
                        "Row was updated or deleted by another transaction "
                                + "(or unsaved-value mapping was incorrect): [com.corebank.customer.domain.Customer#42]"));

        mockMvc.perform(post("/api/v1/customers").with(teller()).contentType(JSON).content(NEW_CUSTOMER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"))
                .andExpect(jsonPath("$.detail").value("The record was modified concurrently; retry the request"))
                .andExpect(jsonPath("$.type").value("https://corebank.example/problems/concurrent-modification"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(content().string(Matchers.not(Matchers.containsString("com.corebank.customer.domain"))));
    }
}
