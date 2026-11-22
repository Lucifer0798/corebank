package com.corebank.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corebank.account.domain.AccountType;
import com.corebank.account.dto.OpenAccountRequest;
import com.corebank.account.service.AccountService;
import com.corebank.common.exception.ApiException;
import com.corebank.common.security.Actor;
import com.corebank.common.security.Actors;
import com.corebank.config.TestDeciders;
import com.corebank.customer.domain.KycStatus;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.service.CustomerService;
import com.corebank.transaction.dto.AmountRequest;
import com.corebank.transaction.dto.TransactionResponse;
import com.corebank.transaction.service.TransactionService;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A claim whose request never finished no longer blocks its key for good, and taking it over never
 * posts twice.
 *
 * <p>Claims are planted directly in the table, the way a crash between claiming and completing
 * leaves one: there is no other way to stop a request halfway.
 */
@SpringBootTest
class IdempotencyClaimTest {

    private static final Actor WORKER = Actor.system("idempotency-claim-test");
    private static final AmountRequest DEPOSIT = new AmountRequest(new BigDecimal("25.00"), "INR", "Claim test");

    @Autowired private IdempotencyService idempotency;
    @Autowired private TransactionService transactionService;
    @Autowired private AccountService accountService;
    @Autowired private CustomerService customerService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MeterRegistry meterRegistry;

    private final ExecutorService threads = Executors.newFixedThreadPool(2);
    private UUID account;
    private String scope;
    private String key;

    @BeforeEach
    void setUp() {
        UUID customer = customerService.create(new CreateCustomerRequest("Claim", "Holder",
                "claim." + UUID.randomUUID() + "@example.com", null, LocalDate.of(1990, 1, 1))).id();
        customerService.updateKyc(customer, KycStatus.VERIFIED, null, TestDeciders.STAFF);
        account = accountService.open(new OpenAccountRequest(customer, AccountType.SAVINGS, "INR", BigDecimal.ZERO)).id();
        scope = "deposit:" + account;
        key = "claim-" + UUID.randomUUID();
    }

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    private BigDecimal balance() {
        return accountService.get(account).balance();
    }

    private long postingsUnderKey() {
        return jdbc.queryForObject("select count(*) from bank_transaction where idempotency_key = ?", Long.class, key);
    }

    private IdempotencyService.Result<TransactionResponse> deposit() {
        return idempotency.execute(scope, key, DEPOSIT, TransactionResponse.class,
                () -> transactionService.deposit(account, DEPOSIT, key));
    }

    /** The row a request leaves when it claimed the key and then never finished. */
    private UUID plantClaim(Instant claimedAt, boolean withToken, Object request) {
        UUID token = withToken ? UUID.randomUUID() : null;
        jdbc.update("""
                insert into idempotency_record (id, scope, idempotency_key, request_hash, status, created_at, claim_token)
                values (?, ?, ?, ?, 'IN_PROGRESS', ?, ?)
                """, UUID.randomUUID(), scope, key, idempotency.requestHash(request), Timestamp.from(claimedAt), token);
        return token;
    }

    /** A request on its own thread. A posting needs someone to attribute it to, as a real one has. */
    private Future<Object> inWorker(Callable<Object> work) {
        return threads.submit(() -> {
            AtomicReference<Object> outcome = new AtomicReference<>();
            Actors.runAs(WORKER, () -> {
                try {
                    outcome.set(work.call());
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            });
            return outcome.get();
        });
    }

    /** Committed at once, apart from the transaction the calling thread is in. */
    private void onAnotherConnection(Runnable work) {
        try {
            threads.submit(work).get(10, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Instant ago(long seconds) {
        return Instant.now().minusSeconds(seconds);
    }

    private static String codeOf(Throwable thrown) {
        return thrown instanceof ApiException api ? api.getCode() : thrown.toString();
    }

    private double takenOver() {
        return meterRegistry.get(IdempotencyService.TAKEN_OVER).counter().count();
    }

    @Test
    @DisplayName("a posting whose claim cannot be completed is rolled back with it, not left posted")
    void theOperationAndItsCompletionCommitTogether() {
        // A retry takes the claim over while this request is still posting: its completion finds the
        // token gone. It used to commit the posting first and complete the claim afterwards, alone.
        assertThatThrownBy(() -> idempotency.execute(scope, key, DEPOSIT, TransactionResponse.class, () -> {
            TransactionResponse posted = transactionService.deposit(account, DEPOSIT, key);
            onAnotherConnection(() -> jdbc.update(
                    "update idempotency_record set claim_token = ? where scope = ? and idempotency_key = ?",
                    UUID.randomUUID(), scope, key));
            return posted;
        })).satisfies(thrown -> assertThat(codeOf(thrown)).isEqualTo("IDEMPOTENCY_CLAIM_LOST"));

        assertThat(balance()).isEqualByComparingTo("0.00");
        assertThat(postingsUnderKey()).isZero();
    }

    @Test
    @DisplayName("a claim abandoned over a minute ago is taken over, and the retry posts once")
    void anAbandonedClaimIsTakenOver() {
        UUID abandoned = plantClaim(ago(300), true, DEPOSIT);
        double before = takenOver();

        IdempotencyService.Result<TransactionResponse> result = deposit();

        assertThat(result.replayed()).isFalse();
        assertThat(balance()).isEqualByComparingTo("25.00");
        assertThat(postingsUnderKey()).isEqualTo(1);
        assertThat(takenOver() - before).isEqualTo(1);
        var record = jdbc.queryForMap("select status, claim_token from idempotency_record where scope = ? and idempotency_key = ?",
                scope, key);
        assertThat(record.get("status")).isEqualTo("COMPLETED");
        assertThat(record.get("claim_token")).isNotEqualTo(abandoned);

        // And from then on it replays like any completed key.
        assertThat(deposit().replayed()).isTrue();
        assertThat(balance()).isEqualByComparingTo("25.00");
    }

    @Test
    @DisplayName("a claim made seconds ago is still in progress, not taken over")
    void aRecentClaimIsLeftAlone() {
        plantClaim(ago(10), true, DEPOSIT);

        assertThatThrownBy(this::deposit).satisfies(thrown -> assertThat(codeOf(thrown)).isEqualTo("REQUEST_IN_PROGRESS"));
        assertThat(balance()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a claim from before tokens existed is never taken over, however old: it may have posted")
    void aClaimWithNoTokenIsNeverTakenOver() {
        plantClaim(ago(86_400), false, DEPOSIT);

        assertThatThrownBy(this::deposit).satisfies(thrown -> assertThat(codeOf(thrown)).isEqualTo("REQUEST_IN_PROGRESS"));
        assertThat(balance()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("an abandoned claim is not taken over by a different request under the same key")
    void aDifferentBodyIsStillRefused() {
        plantClaim(ago(300), true, new AmountRequest(new BigDecimal("99.00"), "INR", "Something else"));

        assertThatThrownBy(this::deposit).satisfies(thrown -> assertThat(codeOf(thrown)).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThat(balance()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("two retries racing for one abandoned claim: the operation runs once")
    void racingRetriesPostOnce() throws Exception {
        plantClaim(ago(300), true, DEPOSIT);
        AtomicInteger runs = new AtomicInteger();
        CyclicBarrier together = new CyclicBarrier(2);

        List<Future<Object>> outcomes = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            outcomes.add(inWorker(() -> {
                together.await(10, TimeUnit.SECONDS);
                try {
                    return idempotency.execute(scope, key, DEPOSIT, TransactionResponse.class, () -> {
                        runs.incrementAndGet();
                        return transactionService.deposit(account, DEPOSIT, key);
                    });
                } catch (ApiException refused) {
                    return refused.getCode();
                }
            }));
        }
        List<Object> results = new ArrayList<>();
        for (Future<Object> outcome : outcomes) {
            results.add(outcome.get(30, TimeUnit.SECONDS));
        }

        assertThat(runs).hasValue(1);
        assertThat(balance()).isEqualByComparingTo("25.00");
        assertThat(postingsUnderKey()).isEqualTo(1);
        // One posted; the other was told it is in progress, or, if it came late, got the replay.
        assertThat(results).filteredOn(r -> r instanceof IdempotencyService.Result<?> res && !res.replayed()).hasSize(1);
    }

    @Test
    @DisplayName("a request that was only slow cannot commit after its claim was taken over")
    void aSlowOriginalLosesToTheRetryThatTookItOver() throws Exception {
        CountDownLatch posted = new CountDownLatch(1);
        CountDownLatch carryOn = new CountDownLatch(1);

        // The original: claims, posts, then stalls before its transaction can commit.
        Future<Object> original = inWorker(() -> {
            try {
                return idempotency.execute(scope, key, DEPOSIT, TransactionResponse.class, () -> {
                    TransactionResponse response = transactionService.deposit(account, DEPOSIT, key);
                    posted.countDown();
                    try {
                        carryOn.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return response;
                });
            } catch (ApiException refused) {
                return refused.getCode();
            }
        });
        assertThat(posted.await(30, TimeUnit.SECONDS)).isTrue();
        UUID originalToken = jdbc.queryForObject("select claim_token from idempotency_record where scope = ? and idempotency_key = ?",
                UUID.class, scope, key);

        // Long enough later that its claim looks abandoned, a retry takes it over.
        jdbc.update("update idempotency_record set created_at = ? where scope = ? and idempotency_key = ?",
                Timestamp.from(ago(300)), scope, key);
        Future<Object> retry = inWorker(() -> {
            try {
                return deposit();
            } catch (ApiException refused) {
                return refused.getCode();
            }
        });
        // Let the original carry on once the retry holds the claim: it must now fail to complete.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (originalToken.equals(jdbc.queryForObject(
                "select claim_token from idempotency_record where scope = ? and idempotency_key = ?", UUID.class, scope, key))) {
            assertThat(System.nanoTime()).as("the retry took the claim over").isLessThan(deadline);
            Thread.sleep(20);
        }
        carryOn.countDown();

        assertThat(original.get(30, TimeUnit.SECONDS)).isEqualTo("IDEMPOTENCY_CLAIM_LOST");
        assertThat(retry.get(30, TimeUnit.SECONDS)).isInstanceOfSatisfying(IdempotencyService.Result.class,
                result -> assertThat(result.replayed()).isFalse());
        // Posted once: the original's posting rolled back with its lost completion.
        assertThat(balance()).isEqualByComparingTo("25.00");
        assertThat(postingsUnderKey()).isEqualTo(1);
    }
}
