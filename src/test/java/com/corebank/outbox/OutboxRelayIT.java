package com.corebank.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.corebank.account.dto.AccountResponse;
import com.corebank.account.dto.OpenAccountRequest;
import com.corebank.account.domain.AccountType;
import com.corebank.account.service.AccountService;
import com.corebank.customer.domain.KycStatus;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.dto.CustomerResponse;
import com.corebank.customer.service.CustomerService;
import com.corebank.outbox.repository.OutboxEventRepository;
import com.corebank.transaction.dto.AmountRequest;
import com.corebank.transaction.dto.TransactionResponse;
import com.corebank.transaction.messaging.TransactionEventPublisher;
import com.corebank.transaction.service.TransactionService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The proof this whole feature exists for: an event written while Kafka is unreachable is not
 * lost, and needs no restart or manual intervention to reach the broker once it comes back.
 *
 * <p>A dedicated class rather than another method on {@code CoreBankTestcontainersIT}, which
 * shares one Kafka container across every test in the class -- stopping it here would disrupt
 * whichever of those tests happens to run around the same time. No {@code @PreAuthorize} lives on
 * the service layer (only on the controllers), so this calls {@code CustomerService}/
 * {@code AccountService}/{@code TransactionService} directly and needs no Keycloak container at
 * all: the thing under test is Kafka delivery, not authentication.
 *
 * <p>{@code WebEnvironment.MOCK} (the default), not {@code NONE}: {@code SecurityConfig}'s
 * {@code apiFilterChain} bean autowires {@code HttpSecurity}, which only exists in a web
 * application context. {@code MOCK} satisfies that without binding a real port -- this test never
 * makes an HTTP call either way.
 */
@Testcontainers
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboxRelayIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("corebank")
            .withUsername("corebank")
            .withPassword("corebank");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));

    // Same PER_CLASS/@Testcontainers ordering race documented at length in
    // CoreBankTestcontainersIT -- see that class for why this has to be a static initializer.
    static {
        Startables.deepStart(POSTGRES, KAFKA).join();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);

        // The mocked-JWT suite's default (src/test/resources/application.yml) is false, since
        // that suite has no broker for the relay to reach. This test's entire point needs it on.
        registry.add("corebank.outbox.relay-enabled", () -> "true");
        // Shorter than the 2s default purely so the test doesn't spend longer than it has to
        // waiting on ticks -- it changes how long the proof takes, not what it proves.
        registry.add("corebank.outbox.relay-interval", () -> "1s");
    }

    @Autowired
    private CustomerService customerService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Test
    @DisplayName("an event written while Kafka is down survives durably and is delivered once Kafka recovers")
    void survivesABrokerOutage() {
        // Set up while Kafka is still healthy, and wait for that setup's own outbox rows to
        // drain, so every assertion below is unambiguously about the deposit posted during the
        // outage rather than tangled up with unrelated customer/account-open events.
        CustomerResponse customer = customerService.create(new CreateCustomerRequest(
                "Outbox", "Resilience", "outbox-" + UUID.randomUUID() + "@example.com", null,
                LocalDate.of(1990, 1, 1)));
        customerService.updateKyc(customer.id(), KycStatus.VERIFIED);
        AccountResponse account = accountService.open(
                new OpenAccountRequest(customer.id(), AccountType.SAVINGS, "INR", BigDecimal.ZERO));

        await().atMost(Duration.ofSeconds(10))
                .until(() -> outboxRepository.countByPublishedAtIsNull() == 0);

        // Pause, not stop: stopping this container and starting it again would create a new
        // container with a new ephemeral host port, which the producer/consumer factories built
        // at context startup have no way to discover -- they resolved spring.kafka.bootstrap-servers
        // once, into a fixed config map, when those beans were created. Pausing suspends the
        // broker process in place (connections hang rather than being refused) while leaving the
        // same container, and therefore the same port mapping, intact for the whole outage.
        KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
        String reference;
        try {
            TransactionResponse deposit = transactionService.deposit(
                    account.id(),
                    new AmountRequest(new BigDecimal("777.00"), "INR", "outbox outage test"),
                    "outbox-outage-" + UUID.randomUUID());
            reference = deposit.reference();

            // The write survives the outage: it is a plain Postgres INSERT in the same
            // transaction as the ledger write (see TransactionEventPublisher), never a Kafka
            // call, so nothing about Kafka being down should have stopped this from committing.
            assertThat(outboxRepository.countByPublishedAtIsNull()).isEqualTo(1);

            // And it stays that way while the outage continues -- proving OutboxRelay's failed
            // sends are genuinely retried, not silently marked done, and don't crash the
            // scheduler. A couple of ticks is enough: relay-interval is 1s here.
            for (int tick = 0; tick < 3; tick++) {
                await().pollDelay(Duration.ofMillis(1100)).atMost(Duration.ofSeconds(2)).until(() -> true);
                assertThat(outboxRepository.countByPublishedAtIsNull())
                        .as("still unpublished while Kafka remains down")
                        .isEqualTo(1);
            }
        } finally {
            KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
        }

        // No restart, no manual replay -- the same relay, still ticking the whole time, delivers
        // it on its own the moment Kafka is reachable again.
        await().atMost(Duration.ofSeconds(20))
                .until(() -> outboxRepository.countByPublishedAtIsNull() == 0);

        // Belt and suspenders: confirm the message genuinely reached the real topic, with a fresh
        // consumer independent of the relay's own bookkeeping, rather than trusting that
        // published_at being set means what it claims -- the same reasoning
        // CoreBankTestcontainersIT's own kafkaEventRoundTrip test applies.
        try (var consumer = new org.apache.kafka.clients.consumer.KafkaConsumer<String, String>(Map.of(
                org.apache.kafka.clients.consumer.ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG, "outbox-outage-verify-" + UUID.randomUUID(),
                org.apache.kafka.clients.consumer.ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                org.apache.kafka.clients.consumer.ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringDeserializer",
                org.apache.kafka.clients.consumer.ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringDeserializer"))) {
            consumer.subscribe(List.of(TransactionEventPublisher.TOPIC));
            String finalReference = reference;
            var found = await().atMost(Duration.ofSeconds(15))
                    .until(() -> {
                        var polled = consumer.poll(Duration.ofMillis(500));
                        for (var record : polled) {
                            if (record.key().equals(finalReference)) {
                                return record;
                            }
                        }
                        return null;
                    }, r -> r != null);
            assertThat(found.value()).contains("outbox outage test");
        }
    }
}
