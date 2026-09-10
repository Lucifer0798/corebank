package com.corebank.outbox;

import com.corebank.common.exception.BusinessRuleException;
import com.corebank.customer.domain.Customer;
import com.corebank.customer.messaging.CustomerChangedEvent;
import com.corebank.customer.messaging.CustomerEventPublisher;
import com.corebank.customer.repository.CustomerRepository;
import com.corebank.outbox.domain.OutboxAggregateType;
import com.corebank.transaction.domain.BankTransaction;
import com.corebank.transaction.messaging.TransactionEventPublisher;
import com.corebank.transaction.messaging.TransactionPostedEvent;
import com.corebank.transaction.repository.BankTransactionRepository;
import java.time.Instant;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repairs a gap left before the outbox existed (or during any window this application's own
 * monitoring missed): re-derives the event a transaction or customer change should have produced
 * from the ledger/customer tables themselves, and writes it as a fresh outbox row through the
 * exact same {@link OutboxEventWriter} path a live request uses -- so a backfilled event is
 * delivered, retried, and observed identically to one written the normal way.
 *
 * <p>Safe to run more than once over the same window: every downstream consumer of these topics
 * (the OpenSearch indexers, the Python insights projection) already upserts by the event's key
 * rather than appending, a property established when those consumers were first built, not
 * something this service adds.
 */
@Service
public class OutboxBackfillService {

    /** Rows fetched per page rather than the whole window at once -- see the repository methods. */
    private static final int PAGE_SIZE = 500;

    private final BankTransactionRepository transactionRepository;
    private final CustomerRepository customerRepository;
    private final OutboxEventWriter outbox;

    public OutboxBackfillService(BankTransactionRepository transactionRepository,
                                  CustomerRepository customerRepository, OutboxEventWriter outbox) {
        this.transactionRepository = transactionRepository;
        this.customerRepository = customerRepository;
        this.outbox = outbox;
    }

    @Transactional
    public int replayTransactions(Instant since, Instant until) {
        requireValidWindow(since, until);
        int count = 0;
        Pageable page = PageRequest.of(0, PAGE_SIZE, Sort.by("postedAt").ascending().and(Sort.by("id")));
        Slice<BankTransaction> slice;
        do {
            slice = transactionRepository.findByPostedAtBetween(since, until, page);
            for (BankTransaction transaction : slice) {
                outbox.write(OutboxAggregateType.TRANSACTION, TransactionEventPublisher.TOPIC,
                        transaction.getReference(), TransactionPostedEvent.from(transaction));
            }
            count += slice.getNumberOfElements();
            page = page.next();
        } while (slice.hasNext());
        return count;
    }

    @Transactional
    public int replayCustomers(Instant since, Instant until) {
        requireValidWindow(since, until);
        int count = 0;
        Pageable page = PageRequest.of(0, PAGE_SIZE, Sort.by("updatedAt").ascending().and(Sort.by("id")));
        Slice<Customer> slice;
        do {
            slice = customerRepository.findByUpdatedAtBetween(since, until, page);
            for (Customer customer : slice) {
                outbox.write(OutboxAggregateType.CUSTOMER, CustomerEventPublisher.TOPIC,
                        customer.getId().toString(), CustomerChangedEvent.from(customer));
            }
            count += slice.getNumberOfElements();
            page = page.next();
        } while (slice.hasNext());
        return count;
    }

    private void requireValidWindow(Instant since, Instant until) {
        if (!until.isAfter(since)) {
            throw new BusinessRuleException("INVALID_REPLAY_WINDOW", "'until' must be after 'since'");
        }
    }
}
