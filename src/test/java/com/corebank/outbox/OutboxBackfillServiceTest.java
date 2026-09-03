package com.corebank.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.corebank.common.exception.BusinessRuleException;
import com.corebank.customer.domain.Customer;
import com.corebank.customer.domain.CustomerStatus;
import com.corebank.customer.domain.KycStatus;
import com.corebank.customer.messaging.CustomerChangedEvent;
import com.corebank.customer.messaging.CustomerEventPublisher;
import com.corebank.customer.repository.CustomerRepository;
import com.corebank.outbox.domain.OutboxAggregateType;
import com.corebank.transaction.domain.BankTransaction;
import com.corebank.transaction.domain.TransactionStatus;
import com.corebank.transaction.domain.TransactionType;
import com.corebank.transaction.messaging.TransactionEventPublisher;
import com.corebank.transaction.messaging.TransactionPostedEvent;
import com.corebank.transaction.repository.BankTransactionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Whether TransactionPostedEvent/CustomerChangedEvent serialize to the exact JSON a live
 * consumer expects is already covered where those types are exercised for real -- this is only
 * about whether the replay window is honoured, whether every match in it gets an outbox row via
 * the same OutboxEventWriter path a live request uses, and whether an invalid window is rejected
 * before either repository is even queried.
 */
@ExtendWith(MockitoExtension.class)
class OutboxBackfillServiceTest {

    @Mock
    private BankTransactionRepository transactionRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private OutboxEventWriter outbox;

    private final Instant since = Instant.parse("2026-01-01T00:00:00Z");
    private final Instant until = Instant.parse("2026-02-01T00:00:00Z");

    private OutboxBackfillService service() {
        return new OutboxBackfillService(transactionRepository, customerRepository, outbox);
    }

    private static BankTransaction transaction(String reference) {
        BankTransaction transaction = new BankTransaction();
        transaction.setId(UUID.randomUUID());
        transaction.setReference(reference);
        transaction.setType(TransactionType.DEPOSIT);
        transaction.setStatus(TransactionStatus.POSTED);
        transaction.setAmount(new BigDecimal("100.00"));
        transaction.setCurrency("INR");
        transaction.setDescription("test");
        transaction.setPostedAt(Instant.now());
        transaction.setCreatedAt(Instant.now());
        return transaction;
    }

    private static Customer customer() {
        Customer customer = new Customer();
        customer.setId(UUID.randomUUID());
        customer.setCustomerNumber("CUST0001");
        customer.setFirstName("Asha");
        customer.setLastName("Menon");
        customer.setEmail("asha@example.com");
        customer.setDateOfBirth(LocalDate.of(1990, 1, 1));
        customer.setKycStatus(KycStatus.VERIFIED);
        customer.setStatus(CustomerStatus.ACTIVE);
        return customer;
    }

    @Test
    @DisplayName("writes one outbox row per matching transaction and returns the count")
    void replaysEveryMatchingTransaction() {
        BankTransaction first = transaction("TXN-1");
        BankTransaction second = transaction("TXN-2");
        when(transactionRepository.findByPostedAtBetween(since, until)).thenReturn(List.of(first, second));

        int count = service().replayTransactions(since, until);

        assertThat(count).isEqualTo(2);
        verify(outbox).write(eq(OutboxAggregateType.TRANSACTION), eq(TransactionEventPublisher.TOPIC),
                eq("TXN-1"), any(TransactionPostedEvent.class));
        verify(outbox).write(eq(OutboxAggregateType.TRANSACTION), eq(TransactionEventPublisher.TOPIC),
                eq("TXN-2"), any(TransactionPostedEvent.class));
    }

    @Test
    @DisplayName("writes one outbox row per matching customer and returns the count")
    void replaysEveryMatchingCustomer() {
        Customer customer = customer();
        when(customerRepository.findByUpdatedAtBetween(since, until)).thenReturn(List.of(customer));

        int count = service().replayCustomers(since, until);

        assertThat(count).isEqualTo(1);
        verify(outbox).write(eq(OutboxAggregateType.CUSTOMER), eq(CustomerEventPublisher.TOPIC),
                eq(customer.getId().toString()), any(CustomerChangedEvent.class));
    }

    @Test
    @DisplayName("an empty window is a no-op, not an error")
    void emptyMatchesAreANoOp() {
        when(transactionRepository.findByPostedAtBetween(since, until)).thenReturn(List.of());

        assertThat(service().replayTransactions(since, until)).isZero();
        verifyNoInteractions(outbox);
    }

    @Test
    @DisplayName("a window where 'until' is not after 'since' is rejected before either repository is queried")
    void invalidWindowIsRejected() {
        Instant notAfter = since.minus(1, ChronoUnit.DAYS);

        assertThatThrownBy(() -> service().replayTransactions(since, notAfter))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("must be after");
        assertThatThrownBy(() -> service().replayCustomers(since, since))
                .isInstanceOf(BusinessRuleException.class);

        verifyNoInteractions(transactionRepository, customerRepository, outbox);
    }
}
