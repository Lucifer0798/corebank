package com.corebank.common.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebank.customer.domain.CustomerStatus;
import com.corebank.customer.domain.KycStatus;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.dto.CustomerResponse;
import com.corebank.customer.service.CustomerService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The failures {@link GlobalExceptionHandler} maps that no feature test reaches on purpose: the
 * two database-level 409s below, and Spring MVC's own client errors further down.
 *
 * <p>The two database-level failures {@link GlobalExceptionHandler} maps to a 409 -- a constraint
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
@ExtendWith(OutputCaptureExtension.class)
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

    // Spring MVC's own client errors. Each is raised by the framework before or around the
    // controller and carries its own 4xx status, yet used to fall through to the catch-all and
    // come back as a 500 logged at ERROR -- telling callers their own malformed request was the
    // server's fault. The one actually seen: a form-encoded POST to unfreeze answered 500, not 415.

    private static final String ANY_ACCOUNT = "/api/v1/accounts/" + UUID.randomUUID();
    private static final String FORM = MediaType.APPLICATION_FORM_URLENCODED_VALUE;

    @Test
    @DisplayName("a body in a content type the endpoint does not read becomes a 415 saying what it does read")
    void unsupportedContentTypeBecomes415() throws Exception {
        mockMvc.perform(post(ANY_ACCOUNT + "/unfreeze").with(teller()).contentType(FORM).content("reason=paperwork"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.type").value("https://corebank.example/problems/unsupported-media-type"))
                .andExpect(jsonPath("$.timestamp").exists())
                // The framework's own headers survive: a 415 should say what it would have accepted.
                .andExpect(header().string("Accept", Matchers.containsString(JSON)));
    }

    @Test
    @DisplayName("malformed JSON becomes a 400 that says nothing about the parser")
    void malformedJsonBecomes400() throws Exception {
        mockMvc.perform(post(ANY_ACCOUNT + "/unfreeze").with(teller()).contentType(JSON).content("{\"reason\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST_BODY"))
                .andExpect(jsonPath("$.type").value("https://corebank.example/problems/malformed-request-body"))
                // Exact, not "does not mention Jackson": the parser's message ("JSON parse error:
                // Unexpected end-of-input ...") never names it, so that check passed with it leaked.
                .andExpect(jsonPath("$.detail")
                        .value("The request body is missing, is not valid JSON, or has a field of the wrong type"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("end-of-input"))));
    }

    @Test
    @DisplayName("an HTTP method the path does not support becomes a 405 with an Allow header")
    void unsupportedMethodBecomes405() throws Exception {
        mockMvc.perform(delete(ANY_ACCOUNT + "/unfreeze").with(teller()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(header().string("Allow", Matchers.containsString("POST")));
    }

    @Test
    @DisplayName("a path variable that is not a UUID becomes a 400 naming the parameter, not the converter")
    void badUuidPathVariableBecomes400() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/not-a-uuid").with(teller()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.detail").value("Parameter 'accountId' has an invalid value"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("java.util.UUID"))));
    }

    @Test
    @DisplayName("a missing required query parameter becomes a 400 naming it")
    void missingQueryParameterBecomes400() throws Exception {
        mockMvc.perform(get("/api/v1/fx/quote").param("from", "USD").param("to", "INR").with(teller()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"))
                .andExpect(jsonPath("$.detail").value("Required parameter 'amount' is missing"));
    }

    @Test
    @DisplayName("asking for a representation the API cannot produce becomes a 406")
    void unacceptableRepresentationBecomes406() throws Exception {
        UUID id = UUID.randomUUID();
        when(customerService.get(id)).thenReturn(new CustomerResponse(id, "C-1", "Ada", "Lovelace",
                "ada@example.com", null, LocalDate.of(1990, 1, 1), KycStatus.VERIFIED, CustomerStatus.ACTIVE,
                false, Instant.now()));

        mockMvc.perform(get("/api/v1/customers/" + id).with(teller()).accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));
    }

    @Test
    @DisplayName("a client error is not logged as an unhandled exception")
    void clientErrorIsNotLoggedAsUnhandled(CapturedOutput output) throws Exception {
        mockMvc.perform(post(ANY_ACCOUNT + "/unfreeze").with(teller()).contentType(FORM).content("reason=paperwork"))
                .andExpect(status().isUnsupportedMediaType());

        assertThat(output).doesNotContain("Unhandled exception");
    }

    @Test
    @DisplayName("a genuine failure is still a 500, logged as unhandled, with nothing leaked")
    void genuineFailureIsStill500(CapturedOutput output) throws Exception {
        UUID id = UUID.randomUUID();
        when(customerService.get(id)).thenThrow(new IllegalStateException("pool exhausted at db-primary-7"));

        mockMvc.perform(get("/api/v1/customers/" + id).with(teller()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("db-primary-7"))));

        // The positive control for the test above: proves the capture sees this logger at all, so
        // its "does not contain" is a check that can fail rather than one that cannot.
        assertThat(output).contains("Unhandled exception");
    }
}
