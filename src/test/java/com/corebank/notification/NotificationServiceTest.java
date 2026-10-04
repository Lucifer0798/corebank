package com.corebank.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.corebank.account.domain.AccountType;
import com.corebank.account.dto.OpenAccountRequest;
import com.corebank.account.service.AccountService;
import com.corebank.account.service.InterestService;
import com.corebank.customer.domain.KycStatus;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.service.CustomerService;
import com.corebank.notification.dto.NotificationResponse;
import com.corebank.notification.repository.NotificationRepository;
import com.corebank.notification.service.NotificationService;
import com.corebank.outbox.OutboxBackfillService;
import com.corebank.outbox.domain.OutboxEvent;
import com.corebank.outbox.repository.OutboxEventRepository;
import com.corebank.transaction.domain.TransactionStatus;
import com.corebank.transaction.dto.AmountRequest;
import com.corebank.transaction.dto.ReversalRequest;
import com.corebank.transaction.dto.TransactionResponse;
import com.corebank.transaction.dto.TransferRequest;
import com.corebank.transaction.messaging.TransactionEventPublisher;
import com.corebank.transaction.messaging.TransactionPostedEvent;
import com.corebank.transaction.service.TransactionService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * What customers are told, driven by the events the system really publishes.
 *
 * <p>Each test performs real postings, captures the exact {@link TransactionPostedEvent}s that would
 * go to Kafka, and hands them to {@link NotificationService} the way the consumer would. No broker is
 * involved -- this suite runs without one -- but the events are the genuine article rather than
 * hand-built ones, so nothing here can drift from what production actually sends.
 */
@SpringBootTest
@RecordApplicationEvents
class NotificationServiceTest {

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private InterestService interestService;

    @Autowired
    private OutboxBackfillService backfill;

    @Autowired
    private OutboxEventRepository outbox;

    @Autowired
    private ApplicationEvents events;

    private UUID asha;
    private UUID ashaAccount;
    private int n;

    @BeforeEach
    void setUp() {
        n = UNIQUE.incrementAndGet();
        asha = customer("Asha");
        ashaAccount = account(asha, "INR");
        events.clear();
    }

    private UUID customer(String name) {
        UUID id = customerService.create(new CreateCustomerRequest(name, "Rao",
                name.toLowerCase() + ".rao." + n + "." + UUID.randomUUID() + "@example.com", null,
                LocalDate.of(1990, 1, 1))).id();
        customerService.updateKyc(id, KycStatus.VERIFIED);
        return id;
    }

    private UUID account(UUID customerId, String currency) {
        return accountService.open(new OpenAccountRequest(customerId, AccountType.SAVINGS, currency, BigDecimal.ZERO)).id();
    }

    /** Feeds everything published since the last clear() to the consumer, as Kafka would. */
    private void deliverPublished() {
        List<TransactionPostedEvent> published = events.stream(TransactionPostedEvent.class).toList();
        published.forEach(notificationService::handle);
        events.clear();
    }

    private List<NotificationResponse> notificationsFor(UUID customerId) {
        return notificationService.forCustomer(customerId, PageRequest.of(0, 50)).getContent();
    }

    @Test
    @DisplayName("a deposit tells its owner, once, that money arrived")
    void aDepositIsAnnounced() {
        transactionService.deposit(ashaAccount, new AmountRequest(new BigDecimal("500.00"), "INR", "Cash"), "nt-dep-" + n);
        deliverPublished();

        List<NotificationResponse> told = notificationsFor(asha);
        // One, not two: the deposit's other leg debits the bank's cash account, which is nobody's.
        assertThat(told).hasSize(1);
        assertThat(told.getFirst().message()).isEqualTo("500.00 INR credited to account " + masked(ashaAccount));
        assertThat(told.getFirst().transactionStatus()).isEqualTo(TransactionStatus.POSTED);
    }

    @Test
    @DisplayName("a redelivered message tells the customer nothing new")
    void aRedeliveryIsANoOp() {
        // The delivery guarantee this whole feature rests on. Kafka delivers at least once and the
        // outbox relay retries, so the same message can arrive again. A naive consumer writes a row
        // per message and the customer is told about one deposit twice.
        transactionService.deposit(ashaAccount, new AmountRequest(new BigDecimal("500.00"), "INR", "Cash"), "nt-redeliver-" + n);
        TransactionPostedEvent event = events.stream(TransactionPostedEvent.class).findFirst().orElseThrow();

        notificationService.handle(event);
        assertThatCode(() -> notificationService.handle(event))
                .describedAs("a second arrival must be a quiet no-op, not a failure the consumer would retry")
                .doesNotThrowAnyException();
        notificationService.handle(event);

        assertThat(notifications.countByTransactionReference(event.reference())).isEqualTo(1);
    }

    @Test
    @DisplayName("a transfer tells each side its own half")
    void aTransferIsAnnouncedToBothParties() {
        transactionService.deposit(ashaAccount, new AmountRequest(new BigDecimal("1000.00"), "INR", "Cash"), "nt-fund-" + n);
        UUID ravi = customer("Ravi");
        UUID raviAccount = account(ravi, "INR");
        events.clear();

        transactionService.transfer(new TransferRequest(ashaAccount, raviAccount, new BigDecimal("300.00"), "INR", "Rent"),
                "nt-xfer-" + n);
        deliverPublished();

        assertThat(notificationsFor(asha)).extracting(NotificationResponse::message)
                .contains("300.00 INR debited from account " + masked(ashaAccount));
        assertThat(notificationsFor(ravi)).extracting(NotificationResponse::message)
                .containsExactly("300.00 INR credited to account " + masked(raviAccount));
    }

    @Test
    @DisplayName("a reversal is announced once, as a reversal -- not also as a fresh debit")
    void aReversalIsAnnouncedOnce() {
        // A reversed deposit publishes three events: the original posting, the correcting REVERSAL
        // posting, and the original again as REVERSED. Announcing all three tells the customer about
        // one reversal twice -- "500 debited" and "your credit was reversed". Only the second is
        // meaningful, so the REVERSAL posting is not announced at all.
        TransactionResponse deposit = transactionService.deposit(ashaAccount,
                new AmountRequest(new BigDecimal("500.00"), "INR", "Mistake"), "nt-rev-dep-" + n);
        transactionService.reverse(deposit.reference(), new ReversalRequest("Keyed twice"), "nt-rev-" + n);
        deliverPublished();

        assertThat(notificationsFor(asha)).extracting(NotificationResponse::message).containsExactlyInAnyOrder(
                "500.00 INR credited to account " + masked(ashaAccount),
                "A credit of 500.00 INR to account " + masked(ashaAccount) + " was reversed");
    }

    @Test
    @DisplayName("the receiving side of an FX transfer is told in its own currency")
    void anFxTransferIsAnnouncedInEachAccountsCurrency() {
        // The transaction's currency is the sender's. A dollar account told it received rupees has
        // been told nonsense, so each notification takes its account's currency and its own leg.
        transactionService.deposit(ashaAccount, new AmountRequest(new BigDecimal("20000.00"), "INR", "Cash"), "nt-fx-fund-" + n);
        UUID dollars = account(asha, "USD");
        events.clear();

        transactionService.transfer(new TransferRequest(ashaAccount, dollars, new BigDecimal("10000.00"), "INR", "To USD"),
                "nt-fx-" + n);
        deliverPublished();

        assertThat(notificationsFor(asha)).extracting(NotificationResponse::message).contains(
                "10000.00 INR debited from account " + masked(ashaAccount),
                "119.40 USD credited to account " + masked(dollars));
    }

    @Test
    @DisplayName("interest is announced as interest")
    void interestIsAnnounced() {
        transactionService.deposit(ashaAccount, new AmountRequest(new BigDecimal("100000.00"), "INR", "Cash"), "nt-int-fund-" + n);
        interestService.accrue(ashaAccount, LocalDate.of(2026, 6, 1));
        events.clear();

        interestService.capitalise(ashaAccount);
        deliverPublished();

        assertThat(notificationsFor(asha)).extracting(NotificationResponse::message)
                .anyMatch(message -> message.contains("interest paid into account " + masked(ashaAccount)));
    }

    @Test
    @DisplayName("a replayed posting announces nothing -- it is history, not news")
    void aReplayIsNotAnnounced() {
        // Search rebuilding a lost index replays the whole ledger through this same topic. Treated as
        // live, that alerts every customer about every posting they ever had. Driven through the
        // real replay, the real outbox row and the consumer's own deserializer, so the flag is proven
        // to survive the wire and not just to exist on the record.
        TransactionResponse deposit = transactionService.deposit(ashaAccount,
                new AmountRequest(new BigDecimal("500.00"), "INR", "Cash"), "nt-replay-" + n);
        TransactionPostedEvent live = events.stream(TransactionPostedEvent.class).findFirst().orElseThrow();
        events.clear();

        Instant postedAt = live.postedAt();
        backfill.replayTransactions(postedAt.minusSeconds(1), postedAt.plusSeconds(1));
        TransactionPostedEvent replayed = newestOutboxEventFor(deposit.reference());

        assertThat(notificationService.handle(replayed)).isZero();
        assertThat(notifications.countByTransactionReference(deposit.reference())).isZero();

        // And a replay leaves nothing behind that would stop the live message being announced.
        assertThat(notificationService.handle(live)).isEqualTo(1);
    }

    /** Reads an outbox row back the way the consumer would receive it from Kafka. */
    private TransactionPostedEvent newestOutboxEventFor(String reference) {
        OutboxEvent row = outbox.findAll().stream()
                .filter(event -> reference.equals(event.getEventKey()))
                .max(Comparator.comparing(OutboxEvent::getCreatedAt))
                .orElseThrow();
        try (JsonDeserializer<TransactionPostedEvent> deserializer = new JsonDeserializer<>(TransactionPostedEvent.class)) {
            deserializer.addTrustedPackages("com.corebank.transaction.messaging");
            return deserializer.deserialize(TransactionEventPublisher.TOPIC,
                    row.getPayload().getBytes(StandardCharsets.UTF_8));
        }
    }

    private String masked(UUID accountId) {
        String number = accountService.get(accountId).accountNumber();
        return "XXXX" + number.substring(number.length() - 4);
    }
}
