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
import java.util.List;
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
        List<BankTransaction> transactions = transactionRepository.findByPostedAtBetween(since, until);
        for (BankTransaction transaction : transactions) {
            outbox.write(OutboxAggregateType.TRANSACTION, TransactionEventPublisher.TOPIC,
                    transaction.getReference(), TransactionPostedEvent.from(transaction));
        }
        return transactions.size();
    }

    @Transactional
    public int replayCustomers(Instant since, Instant until) {
        requireValidWindow(since, until);
        List<Customer> customers = customerRepository.findByUpdatedAtBetween(since, until);
        for (Customer customer : customers) {
            outbox.write(OutboxAggregateType.CUSTOMER, CustomerEventPublisher.TOPIC,
                    customer.getId().toString(), CustomerChangedEvent.from(customer));
        }
        return customers.size();
    }

    private void requireValidWindow(Instant since, Instant until) {
        if (!until.isAfter(since)) {
            throw new BusinessRuleException("INVALID_REPLAY_WINDOW", "'until' must be after 'since'");
        }
    }
}
