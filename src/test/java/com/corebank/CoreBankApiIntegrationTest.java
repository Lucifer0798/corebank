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

    // Deliberately not testing GET /actuator/prometheus here: @SpringBootTest's MOCK web
    // environment (what @AutoConfigureMockMvc drives) does not register the actuator endpoint
    // mapping the way a real embedded servlet container does, so a MockMvc request to any
    // /actuator/* path 404s regardless of the security rule under test. Confirmed instead
    // against the running container: `curl http://localhost:8080/actuator/prometheus` with no
    // Authorization header returns 200 with the expected metrics, and Prometheus's own scrape
    // target for the app shows health "up".
}
