package com.corebank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Walks the full retail journey against a real Spring context and a real database: onboard a
 * customer, verify them, open accounts, move money, and read the statement.
 *
 * <p>Keycloak issues real tokens in production, but standing one up here would mean either a
 * live IdP or Testcontainers, neither of which this test needs in order to check what it
 * actually checks: the application's own authorisation rules. So each request injects a fake
 * {@code Authentication} directly via Spring Security Test's {@code jwt()} post-processor, with
 * the {@code ROLE_*} authority and {@code sub} claim a real Keycloak token would carry after
 * passing through {@link com.corebank.config.SecurityConfig.RealmRoleConverter} -- which is
 * itself tested in isolation, see {@code RealmRoleConverterTest}.
 *
 * <p>The steps run in order and share state on purpose, because that is the sequence a branch
 * actually performs, and because idempotency spans requests. Nothing is rolled back between
 * steps: the idempotency claim commits in its own transaction, so a test-managed rollback would
 * hide the very behaviour being checked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class CoreBankApiIntegrationTest {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String ADMIN_SUBJECT = "it-admin-sub";
    private static final String TELLER_SUBJECT = "it-teller-sub";
    private static final String ASHA_SUBJECT = "it-asha-sub";

    @Autowired
    private MockMvc mockMvc;

    private String customerId;
    private String savingsId;
    private String currentId;

    private static RequestPostProcessor admin() {
        return jwt().jwt(builder -> builder.subject(ADMIN_SUBJECT))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static RequestPostProcessor teller() {
        return jwt().jwt(builder -> builder.subject(TELLER_SUBJECT))
                .authorities(new SimpleGrantedAuthority("ROLE_TELLER"));
    }

    private static RequestPostProcessor customer(String subject) {
        return jwt().jwt(builder -> builder.subject(subject))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    @Test
    @Order(1)
    @DisplayName("an unauthenticated request is refused with a problem document")
    void anonymousRequestsAreRefused() throws Exception {
        mockMvc.perform(get("/api/v1/customers"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @Order(2)
    @DisplayName("a CUSTOMER-role token cannot onboard customers")
    void customerCannotOnboardCustomers() throws Exception {
        mockMvc.perform(post("/api/v1/customers").with(customer("someone"))
                        .contentType(JSON).content("""
                        {"firstName":"X","lastName":"Y","email":"blocked@example.com",
                         "dateOfBirth":"1990-01-01"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @Order(3)
    @DisplayName("a teller onboards a customer, who starts unverified")
    void tellerOnboardsCustomer() throws Exception {
        String body = mockMvc.perform(post("/api/v1/customers").with(teller())
                        .contentType(JSON).content("""
                        {"firstName":"Asha","lastName":"Menon","email":"asha.it@example.com",
                         "phone":"+919876543210","dateOfBirth":"1995-04-17"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kycStatus").value("PENDING"))
                .andExpect(jsonPath("$.identityLinked").value(false))
                .andExpect(jsonPath("$.customerNumber").value(org.hamcrest.Matchers.startsWith("CUST")))
                .andReturn().getResponse().getContentAsString();

        customerId = JsonPath.read(body, "$.id");
    }

    @Test
    @Order(4)
    @DisplayName("an unverified customer cannot be given an account")
    void unverifiedCustomerCannotOpenAccounts() throws Exception {
        mockMvc.perform(post("/api/v1/accounts").with(teller())
                        .contentType(JSON).content("""
                        {"customerId":"%s","accountType":"SAVINGS","currency":"INR"}""".formatted(customerId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_ELIGIBLE"));
    }

    @Test
    @Order(5)
    @DisplayName("KYC is an administrator decision, not a teller one")
    void kycRequiresAdmin() throws Exception {
        mockMvc.perform(patch("/api/v1/customers/{id}/kyc", customerId).with(teller())
                        .contentType(JSON).content("""
                        {"kycStatus":"VERIFIED"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(patch("/api/v1/customers/{id}/kyc", customerId).with(admin())
                        .contentType(JSON).content("""
                        {"kycStatus":"VERIFIED"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kycStatus").value("VERIFIED"));
    }

    @Test
    @Order(6)
    @DisplayName("accounts open at a zero balance, and only CURRENT accounts may carry an overdraft")
    void accountsAreOpened() throws Exception {
        String savings = mockMvc.perform(post("/api/v1/accounts").with(teller())
                        .contentType(JSON).content("""
                        {"customerId":"%s","accountType":"SAVINGS","currency":"INR"}""".formatted(customerId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance").value(0.00))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        savingsId = JsonPath.read(savings, "$.id");

        String current = mockMvc.perform(post("/api/v1/accounts").with(teller())
                        .contentType(JSON).content("""
                        {"customerId":"%s","accountType":"CURRENT","currency":"INR","overdraftLimit":1000.00}"""
                        .formatted(customerId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.availableBalance").value(1000.00))
                .andReturn().getResponse().getContentAsString();
        currentId = JsonPath.read(current, "$.id");

        mockMvc.perform(post("/api/v1/accounts").with(teller())
                        .contentType(JSON).content("""
                        {"customerId":"%s","accountType":"SAVINGS","overdraftLimit":50.00}""".formatted(customerId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OVERDRAFT_NOT_ALLOWED"));
    }

    @Test
    @Order(7)
    @DisplayName("a deposit posts two balanced legs against cash and the customer")
    void depositPostsBothLegs() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/{id}/deposits", savingsId).with(teller())
                        .header("Idempotency-Key", "it-deposit-1")
                        .contentType(JSON).content("""
                        {"amount":25000.00,"currency":"INR","description":"Opening deposit"}"""))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(jsonPath("$.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.legs.length()").value(2))
                .andExpect(jsonPath("$.legs[0].accountNumber").value("GL0000000001"))
                .andExpect(jsonPath("$.legs[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.legs[1].direction").value("CREDIT"))
                .andExpect(jsonPath("$.legs[1].balanceAfter").value(25000.00));
    }

    @Test
    @Order(8)
    @DisplayName("replaying the key returns the original posting instead of depositing twice")
    void replayedDepositDoesNotPostAgain() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/{id}/deposits", savingsId).with(teller())
                        .header("Idempotency-Key", "it-deposit-1")
                        .contentType(JSON).content("""
                        {"amount":25000.00,"currency":"INR","description":"Opening deposit"}"""))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true"));

        mockMvc.perform(get("/api/v1/accounts/{id}/balance", savingsId).with(teller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(25000.00));
    }

    @Test
    @Order(9)
    @DisplayName("reusing a key with a different body is a conflict, not a silent second posting")
    void reusedKeyWithDifferentBodyConflicts() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/{id}/deposits", savingsId).with(teller())
                        .header("Idempotency-Key", "it-deposit-1")
                        .contentType(JSON).content("""
                        {"amount":999.00,"currency":"INR"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    @Order(10)
    @DisplayName("the Idempotency-Key header is mandatory on money movement")
    void idempotencyKeyIsMandatory() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/{id}/deposits", savingsId).with(teller())
                        .contentType(JSON).content("""
                        {"amount":10.00}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
    }

    @Test
    @Order(11)
    @DisplayName("amounts must be positive and no finer than a paisa")
    void amountsAreValidated() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/{id}/deposits", savingsId).with(teller())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(JSON).content("""
                        {"amount":-1.00}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.amount").exists());

        mockMvc.perform(post("/api/v1/accounts/{id}/deposits", savingsId).with(teller())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(JSON).content("""
                        {"amount":10.005}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amount").value("supports at most two decimal places"));
    }

    @Test
    @Order(12)
    @DisplayName("a withdrawal beyond the available balance is refused and changes nothing")
    void withdrawalBeyondBalanceIsRefused() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/{id}/withdrawals", savingsId).with(teller())
                        .header("Idempotency-Key", "it-withdraw-fail")
                        .contentType(JSON).content("""
                        {"amount":40000.00}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        mockMvc.perform(get("/api/v1/accounts/{id}/balance", savingsId).with(teller()))
                .andExpect(jsonPath("$.balance").value(25000.00));
    }

    @Test
    @Order(13)
    @DisplayName("a failed request releases its key, so the client may genuinely retry")
    void failedRequestReleasesItsKey() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/{id}/withdrawals", savingsId).with(teller())
                        .header("Idempotency-Key", "it-withdraw-fail")
                        .contentType(JSON).content("""
                        {"amount":1000.00}"""))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(jsonPath("$.legs[0].balanceAfter").value(24000.00));
    }

    @Test
    @Order(14)
    @DisplayName("an overdraft lets a current account go negative, but only to its limit")
    void overdraftIsHonoured() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/{id}/withdrawals", currentId).with(teller())
                        .header("Idempotency-Key", "it-overdraft-1")
                        .contentType(JSON).content("""
                        {"amount":800.00}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.legs[0].balanceAfter").value(-800.00));

        mockMvc.perform(post("/api/v1/accounts/{id}/withdrawals", currentId).with(teller())
                        .header("Idempotency-Key", "it-overdraft-2")
                        .contentType(JSON).content("""
                        {"amount":300.00}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    @Order(15)
    @DisplayName("a transfer debits one account and credits the other in a single posting")
    void transferMovesMoneyBetweenAccounts() throws Exception {
        mockMvc.perform(post("/api/v1/transfers").with(teller())
                        .header("Idempotency-Key", "it-transfer-1")
                        .contentType(JSON).content("""
                        {"sourceAccountId":"%s","destinationAccountId":"%s","amount":1500.00,
                         "currency":"INR","description":"Cover the overdraft"}"""
                        .formatted(savingsId, currentId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("TRANSFER"))
                .andExpect(jsonPath("$.legs[0].balanceAfter").value(22500.00))
                .andExpect(jsonPath("$.legs[1].balanceAfter").value(700.00));
    }

    @Test
    @Order(16)
    @DisplayName("the statement reads newest first, signed from this account's side")
    void statementIsOrderedAndSigned() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{id}/transactions", savingsId).with(teller())
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].type").value("TRANSFER"))
                .andExpect(jsonPath("$.content[0].signedAmount").value(-1500.00))
                .andExpect(jsonPath("$.content[1].type").value("WITHDRAWAL"))
                .andExpect(jsonPath("$.content[2].type").value("DEPOSIT"))
                .andExpect(jsonPath("$.content[2].signedAmount").value(25000.00));
    }

    @Test
    @Order(17)
    @DisplayName("a CUSTOMER token with no linked customer can authenticate but reads nothing")
    void unlinkedIdentityCanAuthenticateButReadsNoAccounts() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{id}", savingsId).with(customer("someone-not-yet-linked")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @Order(18)
    @DisplayName("linking an identity lets that token read its own accounts and nobody else's")
    void linkingIdentityGrantsOwnershipAccess() throws Exception {
        mockMvc.perform(patch("/api/v1/customers/{id}/identity", customerId).with(teller())
                        .contentType(JSON).content("""
                        {"keycloakSubject":"%s"}""".formatted(ASHA_SUBJECT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identityLinked").value(true));

        mockMvc.perform(get("/api/v1/accounts/{id}", savingsId).with(customer(ASHA_SUBJECT)))
                .andExpect(status().isOk());

        // Someone else's account: the general ledger stands in for any account this identity does not own.
        mockMvc.perform(get("/api/v1/accounts/{id}", "00000000-0000-0000-0000-000000000001")
                        .with(customer(ASHA_SUBJECT)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/accounts/{id}/deposits", savingsId).with(customer(ASHA_SUBJECT))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(JSON).content("""
                        {"amount":100.00}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(19)
    @DisplayName("one Keycloak identity cannot be linked to two customers")
    void identityCannotBeLinkedTwice() throws Exception {
        String otherCustomer = mockMvc.perform(post("/api/v1/customers").with(teller())
                        .contentType(JSON).content("""
                        {"firstName":"Second","lastName":"Customer","email":"second.it@example.com",
                         "dateOfBirth":"1988-01-01"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String otherCustomerId = JsonPath.read(otherCustomer, "$.id");

        mockMvc.perform(patch("/api/v1/customers/{id}/identity", otherCustomerId).with(teller())
                        .contentType(JSON).content("""
                        {"keycloakSubject":"%s"}""".formatted(ASHA_SUBJECT)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDENTITY_ALREADY_LINKED"));
    }

    @Test
    @Order(20)
    @DisplayName("a general-ledger account is not addressable through the customer endpoints")
    void generalLedgerAccountsAreNotAddressable() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/{id}/deposits", "00000000-0000-0000-0000-000000000001")
                        .with(teller())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(JSON).content("""
                        {"amount":100.00}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INTERNAL_ACCOUNT"));
    }

    @Test
    @Order(21)
    @DisplayName("a frozen account rejects postings until it is unfrozen")
    void frozenAccountsRejectPostings() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/{id}/freeze", savingsId).with(teller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));

        mockMvc.perform(post("/api/v1/accounts/{id}/deposits", savingsId).with(teller())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(JSON).content("""
                        {"amount":10.00}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_FROZEN"));

        mockMvc.perform(post("/api/v1/accounts/{id}/unfreeze", savingsId).with(teller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @Order(22)
    @DisplayName("an account holding money cannot be closed")
    void fundedAccountsCannotBeClosed() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/{id}/close", savingsId).with(admin()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BALANCE_NOT_ZERO"));
    }

    @Test
    @Order(23)
    @DisplayName("an unknown account returns a 404 problem document")
    void unknownAccountReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{id}", UUID.randomUUID()).with(teller()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @Order(24)
    @DisplayName("the OpenAPI document is served and describes both security schemes")
    void openApiDocumentIsPublished() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("CoreBank Lite API"))
                .andExpect(jsonPath("$.components.securitySchemes.keycloak.type").value("oauth2"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/api/v1/accounts/{accountId}/deposits']").exists());
    }

    @Test
    @Order(25)
    @DisplayName("a linked customer identity can resolve its own customer record via /me")
    void selfServiceCustomerCanResolveOwnRecord() throws Exception {
        mockMvc.perform(get("/api/v1/customers/me").with(customer(ASHA_SUBJECT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(customerId))
                .andExpect(jsonPath("$.identityLinked").value(true));

        mockMvc.perform(get("/api/v1/customers/me").with(customer("nobody-is-linked-to-this-one")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/customers/me").with(teller()))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(26)
    @DisplayName("only an administrator can trigger an outbox replay")
    void outboxReplayRequiresAdmin() throws Exception {
        mockMvc.perform(post("/api/v1/admin/outbox/replay/transactions").with(teller())
                        .param("since", "2020-01-01T00:00:00Z")
                        .param("until", "2030-01-01T00:00:00Z"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @Order(27)
    @DisplayName("an administrator can replay transaction-posted and customer-changed events for a time window")
    void adminCanReplayOutboxEvents() throws Exception {
        mockMvc.perform(post("/api/v1/admin/outbox/replay/transactions").with(admin())
                        .param("since", "2020-01-01T00:00:00Z")
                        .param("until", "2030-01-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventsEnqueued").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        mockMvc.perform(post("/api/v1/admin/outbox/replay/customers").with(admin())
                        .param("since", "2020-01-01T00:00:00Z")
                        .param("until", "2030-01-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventsEnqueued").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    @Test
    @Order(28)
    @DisplayName("a replay window where 'until' is not after 'since' is refused")
    void outboxReplayRejectsAnInvalidWindow() throws Exception {
        mockMvc.perform(post("/api/v1/admin/outbox/replay/transactions").with(admin())
                        .param("since", "2026-01-01T00:00:00Z")
                        .param("until", "2020-01-01T00:00:00Z"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_REPLAY_WINDOW"));
    }

    @Test
    @Order(29)
    @DisplayName("page and size are bounded, on every paginated endpoint")
    void paginationParametersAreValidated() throws Exception {
        // Every VALIDATION_FAILED assertion elsewhere in this class is about a request *body*
        // (MethodArgumentNotValidException). These constraints live on query parameters instead,
        // which Spring reports as a HandlerMethodValidationException -- a different exception, a
        // different handler branch, and until now nothing exercised it. A refactor that dropped
        // the @Min/@Max annotations would leave `size=100000` happily paging the whole table.
        mockMvc.perform(get("/api/v1/customers").with(teller()).param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                // Keyed by the parameter the caller actually sent, not the "list.page" path the
                // validator reports internally.
                .andExpect(jsonPath("$.errors.page").exists());

        mockMvc.perform(get("/api/v1/customers").with(teller()).param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.size").exists());

        mockMvc.perform(get("/api/v1/customers").with(teller()).param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // The same bounds are declared on the other two paginated endpoints; a copy-paste that
        // dropped them from one would otherwise go unnoticed.
        mockMvc.perform(get("/api/v1/customers/{id}/accounts", customerId).with(teller())
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get("/api/v1/accounts/{id}/transactions", savingsId).with(teller())
                        .param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // The upper bound itself has to remain usable -- an off-by-one in the other direction
        // would reject a perfectly legal page size.
        mockMvc.perform(get("/api/v1/customers").with(teller()).param("size", "100"))
                .andExpect(status().isOk());
    }

    @Test
    @Order(30)
    @DisplayName("a path this API does not serve is a 404, not a 500")
    void unknownPathIsNotFound() throws Exception {
        // Spring routes an unmatched path to the static resource handler, which raises
        // NoResourceFoundException -- previously unhandled, so a mistyped URL came back as a 500
        // and was logged at ERROR with a stack trace, making routine scanner traffic look like
        // the application falling over.
        mockMvc.perform(get("/api/v1/does-not-exist").with(teller()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_FOUND"));

        // A known collection with an unknown sub-path is the same class of mistake. Note this
        // relies on customerId from the ordered steps above -- running this method alone leaves
        // it null, which MockMvc rejects as a malformed URI (a bare 400) long before routing.
        mockMvc.perform(get("/api/v1/customers/{id}/not-a-thing", customerId).with(teller()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_FOUND"));

        // Distinct from a resource that legitimately does not exist: a client branching on `code`
        // needs to tell "your URL is wrong" from "your id is wrong".
        mockMvc.perform(get("/api/v1/customers/{id}", UUID.randomUUID()).with(teller()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @Order(31)
    @DisplayName("an admin can reverse a posting, and the ledger tells the whole story afterwards")
    void postingsCanBeReversed() throws Exception {
        // Posted as a teller, reversed as an admin: moving money and unwinding a movement are
        // deliberately different privileges.
        String posted = mockMvc.perform(post("/api/v1/accounts/{id}/deposits", savingsId).with(teller())
                        .header("Idempotency-Key", "it-reversible-deposit")
                        .contentType(JSON).content("""
                        {"amount":777.00,"currency":"INR","description":"Keyed twice by branch 004"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String reference = JsonPath.read(posted, "$.reference");
        double balanceAfterDeposit = ((Number) JsonPath.read(posted, "$.legs[1].balanceAfter")).doubleValue();

        mockMvc.perform(post("/api/v1/transactions/{reference}/reversal", reference).with(teller())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(JSON).content("""
                        {"reason":"Tellers do not get to unwind postings"}"""))
                .andExpect(status().isForbidden());

        // The correction is its own transaction, with its own reference, mirroring both legs.
        mockMvc.perform(post("/api/v1/transactions/{reference}/reversal", reference).with(admin())
                        .header("Idempotency-Key", "it-reversal-1")
                        .contentType(JSON).content("""
                        {"reason":"Duplicate counter deposit keyed twice by branch 004"}"""))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(jsonPath("$.type").value("REVERSAL"))
                .andExpect(jsonPath("$.reversalOf").value(reference))
                .andExpect(jsonPath("$.amount").value(777.00))
                .andExpect(jsonPath("$.legs[0].accountNumber").value("GL0000000001"))
                .andExpect(jsonPath("$.legs[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.legs[1].direction").value("DEBIT"))
                .andExpect(jsonPath("$.legs[1].balanceAfter").value(balanceAfterDeposit - 777.00));

        // The original is marked, not erased.
        mockMvc.perform(get("/api/v1/transactions/{reference}", reference).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVERSED"))
                .andExpect(jsonPath("$.type").value("DEPOSIT"));

        // Replaying the key returns the same correction rather than unwinding a second time.
        mockMvc.perform(post("/api/v1/transactions/{reference}/reversal", reference).with(admin())
                        .header("Idempotency-Key", "it-reversal-1")
                        .contentType(JSON).content("""
                        {"reason":"Duplicate counter deposit keyed twice by branch 004"}"""))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true"));

        // A genuinely new request to reverse it again is a conflict: it was reversible a moment
        // ago and is not any more.
        mockMvc.perform(post("/api/v1/transactions/{reference}/reversal", reference).with(admin())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(JSON).content("""
                        {"reason":"Second attempt"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_REVERSED"));

        // Both postings sit on the statement, newest first, and net to nothing.
        mockMvc.perform(get("/api/v1/accounts/{id}/transactions", savingsId).with(teller())
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].type").value("REVERSAL"))
                .andExpect(jsonPath("$.content[0].signedAmount").value(-777.00))
                .andExpect(jsonPath("$.content[1].type").value("DEPOSIT"))
                .andExpect(jsonPath("$.content[1].signedAmount").value(777.00));
    }

    @Test
    @Order(32)
    @DisplayName("a reversal cannot itself be reversed, and always needs a reason")
    void reversalsAreTerminalAndRequireAReason() throws Exception {
        String posted = mockMvc.perform(post("/api/v1/accounts/{id}/deposits", savingsId).with(teller())
                        .header("Idempotency-Key", "it-terminal-deposit")
                        .contentType(JSON).content("""
                        {"amount":40.00,"currency":"INR","description":"Another mistake"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String reference = JsonPath.read(posted, "$.reference");

        // A reason is what makes a reversal auditable, so an empty one is refused outright.
        mockMvc.perform(post("/api/v1/transactions/{reference}/reversal", reference).with(admin())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(JSON).content("""
                        {"reason":"   "}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        String reversal = mockMvc.perform(post("/api/v1/transactions/{reference}/reversal", reference).with(admin())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(JSON).content("""
                        {"reason":"Posted against the wrong account"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String reversalReference = JsonPath.read(reversal, "$.reference");

        // Correcting a mistaken reversal means posting the original movement again, not stacking
        // a second correction on top of the first.
        mockMvc.perform(post("/api/v1/transactions/{reference}/reversal", reversalReference).with(admin())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(JSON).content("""
                        {"reason":"Undo the undo"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REVERSAL_NOT_REVERSIBLE"));

        mockMvc.perform(post("/api/v1/transactions/{reference}/reversal", "TXN-does-not-exist").with(admin())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(JSON).content("""
                        {"reason":"Nothing to undo"}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @Order(33)
    @DisplayName("a teller can set up a standing instruction, and the owner can see it")
    void scheduledTransfersCanBeSetUp() throws Exception {
        String today = LocalDate.now().toString();

        String created = mockMvc.perform(post("/api/v1/scheduled-transfers").with(teller())
                        .contentType(JSON).content("""
                        {"sourceAccountId":"%s","destinationAccountId":"%s","amount":100.00,
                         "currency":"INR","description":"Rent","frequency":"MONTHLY","startsOn":"%s"}"""
                        .formatted(savingsId, currentId, today)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.frequency").value("MONTHLY"))
                .andExpect(jsonPath("$.nextRunOn").value(today))
                .andExpect(jsonPath("$.runsCompleted").value(0))
                .andReturn().getResponse().getContentAsString();
        String scheduleId = JsonPath.read(created, "$.id");

        // Unlike a transfer, this needs no Idempotency-Key: a duplicate request leaves a visible
        // second mandate that can be cancelled, not money moved twice.
        mockMvc.perform(get("/api/v1/scheduled-transfers/{id}", scheduleId).with(teller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceAccountId").value(savingsId));

        // Listed against both accounts it names, not just the one paying.
        mockMvc.perform(get("/api/v1/accounts/{id}/scheduled-transfers", currentId).with(teller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(scheduleId));

        // Asha owns these accounts (linked in the identity step above), so she reads her own
        // standing instructions through the same account-scoped rule that guards her statement.
        mockMvc.perform(get("/api/v1/accounts/{id}/scheduled-transfers", savingsId)
                        .with(customer(ASHA_SUBJECT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(post("/api/v1/scheduled-transfers/{id}/cancel", scheduleId).with(teller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.nextRunOn").doesNotExist());

        // Cancelling a stopped mandate is a rule violation, not a silent success -- a caller
        // retrying needs to know the second call did nothing.
        mockMvc.perform(post("/api/v1/scheduled-transfers/{id}/cancel", scheduleId).with(teller()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SCHEDULE_NOT_ACTIVE"));
    }

    @Test
    @Order(34)
    @DisplayName("a schedule that could never run sensibly is refused at creation")
    void impossibleSchedulesAreRefused() throws Exception {
        String yesterday = LocalDate.now().minusDays(1).toString();
        String today = LocalDate.now().toString();

        // Backdating would fire immediately and then keep firing until it caught up -- posting a
        // year of a monthly instruction in one afternoon.
        mockMvc.perform(post("/api/v1/scheduled-transfers").with(teller())
                        .contentType(JSON).content("""
                        {"sourceAccountId":"%s","destinationAccountId":"%s","amount":100.00,
                         "frequency":"DAILY","startsOn":"%s"}"""
                        .formatted(savingsId, currentId, yesterday)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SCHEDULE_STARTS_IN_PAST"));

        mockMvc.perform(post("/api/v1/scheduled-transfers").with(teller())
                        .contentType(JSON).content("""
                        {"sourceAccountId":"%s","destinationAccountId":"%s","amount":100.00,
                         "frequency":"DAILY","startsOn":"%s"}"""
                        .formatted(savingsId, savingsId, today)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SAME_ACCOUNT_TRANSFER"));

        // A window ending before the first occurrence would otherwise persist as a mandate that
        // is permanently active and never due.
        mockMvc.perform(post("/api/v1/scheduled-transfers").with(teller())
                        .contentType(JSON).content("""
                        {"sourceAccountId":"%s","destinationAccountId":"%s","amount":100.00,
                         "frequency":"DAILY","startsOn":"%s","endsOn":"%s"}"""
                        .formatted(savingsId, currentId, today, yesterday)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));

        mockMvc.perform(get("/api/v1/scheduled-transfers/{id}", UUID.randomUUID()).with(teller()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    // Deliberately not testing GET /actuator/prometheus here: @SpringBootTest's MOCK web
    // environment (what @AutoConfigureMockMvc drives) does not register the actuator endpoint
    // mapping the way a real embedded servlet container does, so a MockMvc request to any
    // /actuator/* path 404s regardless of the security rule under test. Confirmed instead
    // against the running container: `curl http://localhost:8080/actuator/prometheus` with no
    // Authorization header returns 200 with the expected metrics, and Prometheus's own scrape
    // target for the app shows health "up".
}
