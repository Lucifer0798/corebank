package com.corebank.account.service;

import com.corebank.account.domain.Account;
import com.corebank.account.domain.AccountHold;
import com.corebank.account.domain.HoldStatus;
import com.corebank.account.dto.CaptureHoldRequest;
import com.corebank.account.dto.HoldResponse;
import com.corebank.account.dto.PlaceHoldRequest;
import com.corebank.account.repository.AccountHoldRepository;
import com.corebank.common.Money;
import com.corebank.common.exception.BusinessRuleException;
import com.corebank.common.exception.ResourceNotFoundException;
import com.corebank.transaction.dto.AmountRequest;
import com.corebank.transaction.dto.TransactionResponse;
import com.corebank.transaction.service.ReferenceGenerator;
import com.corebank.transaction.service.TransactionService;
import com.corebank.transaction.service.VelocityLimits;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authorisation holds: reserve money now, move it later or not at all.
 *
 * <p>Every method here locks the account row before touching {@code heldAmount}, because that
 * column is the account's share of the truth about what is reserved and two concurrent
 * authorisations reading it at once would each think the money was free.
 */
@Service
public class HoldService {

    private static final Logger log = LoggerFactory.getLogger(HoldService.class);

    /** The usual card-scheme authorisation window, and the default when a caller names none. */
    private static final Duration DEFAULT_EXPIRY = Duration.ofDays(7);

    /** One sweep tick. Bounded so a large backlog is worked through over several ticks. */
    static final int SWEEP_BATCH_SIZE = 50;

    private final AccountHoldRepository holds;
    private final AccountService accountService;
    private final TransactionService transactionService;
    private final ReferenceGenerator referenceGenerator;
    private final VelocityLimits velocityLimits;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public HoldService(AccountHoldRepository holds,
                       AccountService accountService,
                       TransactionService transactionService,
                       ReferenceGenerator referenceGenerator,
                       VelocityLimits velocityLimits,
                       MeterRegistry meterRegistry,
                       Clock clock) {
        this.holds = holds;
        this.accountService = accountService;
        this.transactionService = transactionService;
        this.referenceGenerator = referenceGenerator;
        this.velocityLimits = velocityLimits;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Transactional
    public HoldResponse place(UUID accountId, PlaceHoldRequest request) {
        BigDecimal amount = Money.normalize(request.amount());
        String currency = request.currency() == null ? Money.BASE_CURRENCY : request.currency();

        Account account = accountService.requireForUpdate(accountId);
        if (!account.isCustomerAccount()) {
            throw new BusinessRuleException("INTERNAL_ACCOUNT",
                    "General-ledger accounts cannot be used through this endpoint");
        }
        account.assertCurrency(currency);

        // The velocity check belongs here rather than at capture. A capture is exempt -- refusing
        // one would undo the guarantee the hold exists for -- so if authorisation went unchecked,
        // holds would be a way around the daily ceiling entirely. Today's other outstanding holds
        // count alongside today's settled debits.
        Instant[] today = velocityLimits.todayBounds();
        velocityLimits.assertWithin(accountId, amount,
                holds.sumOutstandingPlacedBetween(accountId, today[0], today[1]));

        // Throws InsufficientFundsException when the available balance -- already net of other
        // outstanding holds -- will not cover it.
        account.placeHold(amount);

        Instant now = Instant.now(clock);
        AccountHold hold = new AccountHold();
        hold.setReference(referenceGenerator.next("HLD"));
        hold.setAccount(account);
        hold.setAmount(amount);
        hold.setCurrency(currency);
        hold.setDescription(request.description());
        hold.setPlacedAt(now);
        hold.setExpiresAt(now.plus(request.expiresInHours() == null
                ? DEFAULT_EXPIRY
                : Duration.ofHours(request.expiresInHours())));

        accountService.evictCache(accountId);
        count("placed");
        return HoldResponse.from(holds.save(hold));
    }

    /**
     * Claims a hold, in whole or in part.
     *
     * <p>The reservation is freed <em>before</em> the posting, and the order is the point. A
     * capture for the held amount or less is then guaranteed to succeed, because the money it
     * needs is exactly the money that was reserved for it. A capture for more -- a tip added
     * after the pre-authorisation, the case every card scheme has to handle -- finds only the
     * excess competing with the ordinary available balance, and is refused if the account cannot
     * cover that part. Since all of this is one transaction, a refusal rolls the release back and
     * leaves the hold intact to be captured for less or released.
     */
    @Transactional
    public HoldResponse capture(String reference, CaptureHoldRequest request) {
        Instant now = Instant.now(clock);
        AccountHold hold = holds.lockByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Hold", reference));
        hold.assertCapturable(now);

        BigDecimal amount = request.amount() == null
                ? hold.getAmount()
                : Money.normalize(request.amount());

        Account account = accountService.requireForUpdate(hold.getAccount().getId());
        account.freeHold(hold.getAmount());

        TransactionResponse posting = transactionService.withdraw(
                account.getId(),
                new AmountRequest(amount, hold.getCurrency(), captureDescription(hold)),
                hold.getReference(),
                // The velocity limit was applied when the hold was placed. Applying it again here
                // could refuse a capture the hold exists to guarantee.
                false);

        hold.settle(HoldStatus.CAPTURED, now, posting.reference());
        count("captured");
        log.info("Captured hold {} for {} against account {}", reference, amount, account.getId());
        return HoldResponse.from(hold);
    }

    /** Gives up a reservation without claiming it. The money becomes spendable again at once. */
    @Transactional
    public HoldResponse release(String reference) {
        AccountHold hold = holds.lockByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Hold", reference));
        // Deliberately not assertCapturable: an expired-but-unswept hold is still worth
        // releasing, and refusing would leave it reserving money until the sweep caught up.
        if (hold.getStatus() != HoldStatus.ACTIVE) {
            throw new com.corebank.common.exception.ConflictException("HOLD_NOT_ACTIVE",
                    "Hold " + reference + " has already been " + hold.getStatus().name().toLowerCase());
        }
        settleWithoutPosting(hold, HoldStatus.RELEASED);
        count("released");
        return HoldResponse.from(hold);
    }

    @Transactional(readOnly = true)
    public HoldResponse get(String reference) {
        return HoldResponse.from(holds.findByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Hold", reference)));
    }

    @Transactional(readOnly = true)
    public Page<HoldResponse> listForAccount(UUID accountId, Pageable pageable) {
        accountService.require(accountId);
        return holds.findByAccountIdOrderByPlacedAtDesc(accountId, pageable).map(HoldResponse::from);
    }

    // --- The sweep --------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<UUID> findExpired() {
        return holds.findExpiredIds(HoldStatus.ACTIVE, Instant.now(clock),
                PageRequest.of(0, SWEEP_BATCH_SIZE));
    }

    /**
     * Expires one hold, in its own transaction so a failure cannot take the rest of the batch
     * with it. Re-checks under the lock, because between the sweep listing this id and getting
     * here it may have been captured or released by a request.
     */
    @Transactional
    public void expire(UUID holdId) {
        Instant now = Instant.now(clock);
        holds.findById(holdId)
                .flatMap(existing -> holds.lockByReference(existing.getReference()))
                .filter(hold -> hold.hasExpiredBy(now))
                .ifPresent(hold -> {
                    settleWithoutPosting(hold, HoldStatus.EXPIRED);
                    count("expired");
                    log.info("Expired hold {} on account {}", hold.getReference(), hold.getAccount().getId());
                });
    }

    /** The shared half of releasing and expiring: free the reservation, record the outcome. */
    private void settleWithoutPosting(AccountHold hold, HoldStatus outcome) {
        Account account = accountService.requireForUpdate(hold.getAccount().getId());
        account.freeHold(hold.getAmount());
        hold.settle(outcome, Instant.now(clock), null);
        accountService.evictCache(account.getId());
    }

    private String captureDescription(AccountHold hold) {
        return hold.getDescription() == null
                ? "Capture of " + hold.getReference()
                : hold.getDescription();
    }

    private void count(String outcome) {
        meterRegistry.counter("corebank.holds", "outcome", outcome).increment();
    }
}
