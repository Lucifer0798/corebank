package com.corebank.schedule.service;

import com.corebank.account.domain.Account;
import com.corebank.account.service.AccountService;
import com.corebank.common.Money;
import com.corebank.common.exception.BusinessRuleException;
import com.corebank.common.exception.ResourceNotFoundException;
import com.corebank.config.CoreBankProperties;
import com.corebank.idempotency.IdempotencyService;
import com.corebank.schedule.domain.ScheduleStatus;
import com.corebank.schedule.domain.ScheduledTransfer;
import com.corebank.schedule.dto.CreateScheduledTransferRequest;
import com.corebank.schedule.dto.ScheduledTransferResponse;
import com.corebank.schedule.repository.ScheduledTransferRepository;
import com.corebank.transaction.dto.TransactionResponse;
import com.corebank.transaction.dto.TransferRequest;
import com.corebank.transaction.service.TransactionService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Standing instructions, and the machinery for honouring them.
 *
 * <p>The three methods the runner calls -- {@link #claim}, {@link #execute}, {@link #markSuccess}
 * / {@link #markFailure} -- are deliberately three separate transactions rather than one, and the
 * reason is subtle enough to be worth stating.
 *
 * <p>{@code IdempotencyService} records a key's completion in its <em>own</em> committed
 * transaction, immediately after the operation it guards returns. Over HTTP that is safe, because
 * nothing wraps the call: the transfer's transaction commits first, and only then is the key
 * marked complete. Run the same call inside an enclosing transaction and that order inverts --
 * the transfer would merely have joined the caller's transaction, so a later rollback would leave
 * a key recorded as completed with no posting behind it, and every future retry of that occurrence
 * would replay a transfer that never happened. So {@link #execute} runs outside a transaction,
 * exactly as the controller does.
 *
 * <p>What makes the resulting gaps safe is the derived idempotency key. Crash between moving the
 * money and advancing the schedule and the occurrence is simply due again next tick: the transfer
 * replays from the stored response instead of posting twice, and the bookkeeping then completes.
 * The row lock in {@link #claim} is an optimisation -- it stops two replicas doing the same work
 * -- not the correctness guarantee. That is why {@link #markSuccess} and {@link #markFailure}
 * both re-check the due date before touching anything: whoever gets there second must do nothing.
 */
@Service
public class ScheduledTransferService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTransferService.class);

    private final ScheduledTransferRepository schedules;
    private final AccountService accountService;
    private final TransactionService transactionService;
    private final IdempotencyService idempotencyService;
    private final CoreBankProperties.ScheduledTransfers properties;
    private final Clock clock;

    public ScheduledTransferService(ScheduledTransferRepository schedules,
                                    AccountService accountService,
                                    TransactionService transactionService,
                                    IdempotencyService idempotencyService,
                                    CoreBankProperties properties,
                                    Clock clock) {
        this.schedules = schedules;
        this.accountService = accountService;
        this.transactionService = transactionService;
        this.idempotencyService = idempotencyService;
        this.properties = properties.scheduledTransfers();
        this.clock = clock;
    }

    // --- The request-driven half ------------------------------------------------------------

    @Transactional
    public ScheduledTransferResponse create(CreateScheduledTransferRequest request) {
        if (request.sourceAccountId().equals(request.destinationAccountId())) {
            throw new BusinessRuleException("SAME_ACCOUNT_TRANSFER",
                    "The source and destination accounts must differ");
        }
        LocalDate today = LocalDate.now(clock);
        if (request.startsOn().isBefore(today)) {
            // A start date in the past would fire immediately, and then keep firing until it had
            // caught up with today -- posting the whole of a backdated year in one afternoon.
            throw new BusinessRuleException("SCHEDULE_STARTS_IN_PAST",
                    "A schedule cannot start before today");
        }
        if (request.endsOn() != null && request.endsOn().isBefore(request.startsOn())) {
            throw new BusinessRuleException("INVALID_DATE_RANGE",
                    "The end of the schedule must not be before its start");
        }

        String currency = request.currency() == null ? Money.BASE_CURRENCY : request.currency();
        requirePostableCustomerAccount(request.sourceAccountId(), currency);
        requirePostableCustomerAccount(request.destinationAccountId(), currency);

        ScheduledTransfer schedule = new ScheduledTransfer();
        schedule.setSourceAccountId(request.sourceAccountId());
        schedule.setDestinationAccountId(request.destinationAccountId());
        schedule.setAmount(Money.normalize(request.amount()));
        schedule.setCurrency(currency);
        schedule.setDescription(request.description());
        schedule.setFrequency(request.frequency());
        schedule.setStartsOn(request.startsOn());
        schedule.setEndsOn(request.endsOn());
        schedule.schedule();

        return ScheduledTransferResponse.from(schedules.save(schedule));
    }

    @Transactional(readOnly = true)
    public ScheduledTransferResponse get(UUID id) {
        return ScheduledTransferResponse.from(require(id));
    }

    @Transactional(readOnly = true)
    public Page<ScheduledTransferResponse> listForAccount(UUID accountId, Pageable pageable) {
        accountService.require(accountId);
        return schedules.findForAccount(accountId, pageable).map(ScheduledTransferResponse::from);
    }

    @Transactional
    public ScheduledTransferResponse cancel(UUID id) {
        ScheduledTransfer schedule = require(id);
        schedule.cancel();
        return ScheduledTransferResponse.from(schedule);
    }

    // --- The half nobody asked for ------------------------------------------------------------

    /** Ids that have come due on or before {@code on}, oldest first. */
    @Transactional(readOnly = true)
    public List<UUID> findDue(LocalDate on) {
        return schedules.findDueIds(ScheduleStatus.ACTIVE, on, PageRequest.of(0, properties.batchSize()));
    }

    /**
     * Takes the row lock and confirms the mandate is still due, returning everything the transfer
     * needs. Empty when another replica holds the row, or when it stopped being due between the
     * poll and now.
     */
    @Transactional
    public Optional<DueOccurrence> claim(UUID id, LocalDate on) {
        return schedules.lockById(id)
                .filter(schedule -> schedule.isDueOn(on))
                .map(schedule -> new DueOccurrence(
                        schedule.getId(),
                        schedule.getNextRunOn(),
                        schedule.idempotencyKeyFor(schedule.getNextRunOn()),
                        new TransferRequest(
                                schedule.getSourceAccountId(),
                                schedule.getDestinationAccountId(),
                                schedule.getAmount(),
                                schedule.getCurrency(),
                                schedule.getDescription())));
    }

    /**
     * Posts one occurrence. Intentionally not transactional -- see this class's own javadoc for
     * why wrapping it would make a rollback look like a completed idempotency key.
     */
    public TransactionResponse execute(DueOccurrence occurrence) {
        return idempotencyService.execute(
                "scheduled-transfer", occurrence.idempotencyKey(), occurrence.request(),
                TransactionResponse.class,
                () -> transactionService.transfer(occurrence.request(), occurrence.idempotencyKey())).value();
    }

    @Transactional
    public void markSuccess(UUID id, LocalDate dueOn) {
        advanceIfStillDue(id, dueOn, schedule -> schedule.recordSuccess(dueOn));
    }

    @Transactional
    public void markFailure(UUID id, LocalDate dueOn, String error) {
        advanceIfStillDue(id, dueOn,
                schedule -> schedule.recordFailure(dueOn, error, properties.maxConsecutiveFailures()));
    }

    /**
     * The guard that makes the runner's three-transaction shape safe. Whoever advances the mandate
     * first moves {@code nextRunOn} past {@code dueOn}; anyone arriving afterwards -- a second
     * replica, or this instance re-running after a crash -- finds it no longer matches and stops.
     * Without it, two replicas that both got through {@code execute} (the second replaying the
     * stored response rather than posting) would advance the schedule twice and silently skip an
     * occurrence.
     */
    private void advanceIfStillDue(UUID id, LocalDate dueOn, java.util.function.Consumer<ScheduledTransfer> action) {
        Optional<ScheduledTransfer> locked = schedules.lockById(id);
        if (locked.isEmpty()) {
            return;
        }
        ScheduledTransfer schedule = locked.get();
        if (!dueOn.equals(schedule.getNextRunOn()) || schedule.getStatus() != ScheduleStatus.ACTIVE) {
            log.debug("Schedule {} was already advanced past {}; leaving it alone", id, dueOn);
            return;
        }
        action.accept(schedule);
    }

    private ScheduledTransfer require(UUID id) {
        return schedules.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ScheduledTransfer", id.toString()));
    }

    private void requirePostableCustomerAccount(UUID accountId, String currency) {
        Account account = accountService.require(accountId);
        if (!account.isCustomerAccount()) {
            throw new BusinessRuleException("INTERNAL_ACCOUNT",
                    "General-ledger accounts cannot be used through this endpoint");
        }
        account.assertPostable();
        account.assertCurrency(currency);
    }

    /** One due occurrence, flattened so the runner needs no open session to read it. */
    public record DueOccurrence(UUID scheduleId, LocalDate dueOn, String idempotencyKey, TransferRequest request) {

        public BigDecimal amount() {
            return request.amount();
        }
    }
}
