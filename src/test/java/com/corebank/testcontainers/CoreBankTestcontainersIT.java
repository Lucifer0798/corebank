package com.corebank.testcontainers;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

import com.redis.testcontainers.RedisContainer;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * The counterpart to {@code CoreBankApiIntegrationTest}: that suite injects a fake JWT and
 * lets Redis/Kafka be absent, deliberately, so it never needs a live IdP or broker. Real
 * PostgreSQL, Keycloak, Redis and Kafka run here instead, because four real bugs during Phase
 * 2 and 3 -- Keycloak's access token missing {@code sub}, Redis's polymorphic serializer
 * throwing on a cross-caller cache read, Kafka's producer blocking a request thread for 60s,
 * and Tempo binding a receiver to {@code 127.0.0.1} -- were only ever visible against the
 * genuinely running stack. This suite exists so that class of bug gets caught by {@code mvn
 * test}, not by manually curling a container weeks later.
 *
 * <p>Slow and heavy on purpose (container startup dominates the runtime), so it stays out of
 * the everyday {@code CoreBankApiIntegrationTest} loop rather than replacing it.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class CoreBankTestcontainersIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("corebank")
            .withUsername("corebank")
            .withPassword("corebank");

    @Container
    static final RedisContainer REDIS = new RedisContainer(DockerImageName.parse("redis:8.8-alpine"));

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));

    /**
     * A plain container rather than the dasniko convenience wrapper, so the exact startup
     * command matches compose.yaml's precisely rather than trusting a third-party module's
     * interpretation of it. The real {@code keycloak/corebank-realm.json} is mounted directly
     * (no copy to keep in sync) via {@code MountableFile.forHostPath}, resolved relative to the
     * Maven working directory.
     *
     * <p>{@code KC_HOSTNAME} is deliberately left unset here, unlike compose.yaml's fixed value:
     * Testcontainers assigns a random host port per run, so there is no fixed URL to pin it to.
     * Left unset, Keycloak computes each token's issuer from the request's own Host header --
     * fine here because both the test's token requests and the app's own validation hit the
     * same container through the same mapped host:port.
     */
    @Container
    static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(
            DockerImageName.parse("quay.io/keycloak/keycloak:26.7.2"))
            .withCommand("start-dev", "--import-realm")
            .withEnv("KEYCLOAK_ADMIN", "admin")
            .withEnv("KEYCLOAK_ADMIN_PASSWORD", "admin")
            .withEnv("KC_HTTP_ENABLED", "true")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("keycloak/corebank-realm.json"),
                    "/opt/keycloak/data/import/corebank-realm.json")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/realms/corebank/.well-known/openid-configuration")
                    .forStatusCode(200)
                    // 2 minutes was not enough: a cold "start-dev" pays for Quarkus augmentation
                    // (~50s) plus Liquibase schema init on top of realm import, observed taking
                    // ~130s+ total on this machine.
                    .withStartupTimeout(Duration.ofMinutes(5)));

    /**
     * With {@code @TestInstance(PER_CLASS)}, JUnit creates the test instance -- which triggers
     * Spring's context loading and {@code @DynamicPropertySource} -- via a
     * {@code TestInstancePostProcessor}, and that runs *before* {@code @Testcontainers}' own
     * {@code @BeforeAll} container-start callback. Left alone, {@code properties()} below reads
     * {@code getMappedPort()} on containers that haven't started yet. Starting them here, in a
     * static initializer, sidesteps the ordering entirely: it runs at class-load time, ahead of
     * both callbacks.
     */
    static {
        org.testcontainers.lifecycle.Startables.deepStart(POSTGRES, REDIS, KAFKA, KEYCLOAK).join();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // src/test/resources/application.yml sits earlier on the test classpath than
        // src/main/resources/application.yml, and Spring Boot loads a single classpath:application.yml
        // rather than merging both -- so every property the main file sets and the test file
        // doesn't repeat (H2 driver, StringSerializer-by-default producer values) silently reverts
        // to the test/default value here. Both overrides below exist for that reason, not because
        // this test's own config disagrees with production's.
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));

        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // Without this, the producer falls back to Boot's default StringSerializer and throws
        // SerializationException the moment a real TransactionPostedEvent is published -- a bug
        // that was invisible until this suite ran a real producer against a real broker for the
        // first time.
        registry.add("spring.kafka.producer.value-serializer",
                () -> "org.springframework.kafka.support.serializer.JacksonJsonSerializer");
        // Same shadowing on the consumer side: without it, the app's own @KafkaListener
        // (TransactionEventLogger) fails every message with MessageConversionException trying to
        // hand a raw JSON string to a handler expecting TransactionPostedEvent.
        registry.add("spring.kafka.consumer.value-deserializer",
                () -> "org.springframework.kafka.support.serializer.JacksonJsonDeserializer");
        registry.add("spring.kafka.consumer.properties.spring.json.trusted.packages",
                () -> "com.corebank.transaction.messaging");

        String issuer = keycloakBaseUrl() + "/realms/corebank";
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> issuer);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> issuer + "/protocol/openid-connect/certs");
    }

    private static String keycloakBaseUrl() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080);
    }

    @LocalServerPort
    private int port;

    private String tellerToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        RestAssured.baseURI = "http://localhost:" + port;
        RestAssured.basePath = "/api/v1";
        if (tellerToken == null) {
            tellerToken = tokenFor("teller1", "Teller#2025");
            adminToken = tokenFor("admin", "ChangeMe#2025!");
        }
    }

    private String tokenFor(String username, String password) {
        return given()
                .baseUri(keycloakBaseUrl())
                .basePath("/realms/corebank/protocol/openid-connect/token")
                .contentType(ContentType.URLENC)
                .formParam("grant_type", "password")
                .formParam("client_id", "corebank-web")
                .formParam("username", username)
                .formParam("password", password)
                .post()
                .then().statusCode(200)
                .extract().path("access_token");
    }

    @Test
    @Order(1)
    @DisplayName("a real Keycloak-issued token authenticates and carries a usable sub claim")
    void realKeycloakTokenWorks() {
        given().header("Authorization", "Bearer " + tellerToken)
                .when().get("/customers")
                .then().statusCode(200);
    }

    @Test
    @Order(2)
    @DisplayName("the full deposit flow works against real Postgres, real Redis and real Kafka together")
    void fullFlowAgainstRealInfrastructure() {
        String customerId = given()
                .header("Authorization", "Bearer " + tellerToken)
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "firstName", "Testcontainers",
                        "lastName", "Verification",
                        "email", "tc-" + UUID.randomUUID() + "@example.com",
                        "dateOfBirth", "1990-01-01"))
                .post("/customers")
                .then().statusCode(201)
                .extract().path("id");

        given().header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(Map.of("kycStatus", "VERIFIED"))
                .patch("/customers/{id}/kyc", customerId)
                .then().statusCode(200);

        String accountId = given()
                .header("Authorization", "Bearer " + tellerToken)
                .contentType(ContentType.JSON)
                .body(Map.of("customerId", customerId, "accountType", "SAVINGS"))
                .post("/accounts")
                .then().statusCode(201)
                .extract().path("id");

        // First read: populates the real Redis cache. This is exactly the sequence that hid a
        // ClassCastException in Phase 3 -- it only ever surfaced on a read by someone other than
        // whoever populated the entry, which a single-caller test cannot reproduce.
        given().header("Authorization", "Bearer " + tellerToken)
                .get("/accounts/{id}", accountId)
                .then().statusCode(200).body("balance", equalTo(0.00f));

        given().header("Authorization", "Bearer " + adminToken)
                .header("Idempotency-Key", "tc-deposit-" + UUID.randomUUID())
                .contentType(ContentType.JSON)
                .body(Map.of("amount", 500.00, "description", "Testcontainers deposit"))
                .post("/accounts/{id}/deposits", accountId)
                .then().statusCode(201).body("legs[1].balanceAfter", equalTo(500.00f));

        // Second read, by a *different* token than the one that populated the cache entry --
        // the case the mocked test suite structurally cannot exercise, since it never runs a
        // real Redis at all.
        given().header("Authorization", "Bearer " + adminToken)
                .get("/accounts/{id}", accountId)
                .then().statusCode(200).body("balance", equalTo(500.00f));
    }

    @Test
    @Order(3)
    @DisplayName("a posted transaction is actually published to and consumed from a real Kafka broker")
    void kafkaEventRoundTrip() {
        // The application's own @KafkaListener already consumed and logged this posting as a
        // side effect of the previous test; a fresh consumer proves the message really landed
        // on the real broker rather than trusting that side effect alone.
        try (var consumer = new org.apache.kafka.clients.consumer.KafkaConsumer<String, String>(Map.of(
                org.apache.kafka.clients.consumer.ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG, "tc-verify-" + UUID.randomUUID(),
                org.apache.kafka.clients.consumer.ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                org.apache.kafka.clients.consumer.ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringDeserializer",
                org.apache.kafka.clients.consumer.ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringDeserializer"))) {
            consumer.subscribe(List.of("corebank.transactions.posted"));
            var records = await().atMost(Duration.ofSeconds(10))
                    .until(() -> consumer.poll(Duration.ofMillis(500)),
                            polled -> polled.count() > 0);
            assertThat(records.count()).isPositive();
        }
    }
}
