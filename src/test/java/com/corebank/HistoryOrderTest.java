package com.corebank;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebank.account.domain.AccountStatus;
import com.corebank.account.domain.AccountStatusChange;
import com.corebank.account.domain.AccountType;
import com.corebank.account.dto.AccountStatusChangeResponse;
import com.corebank.account.dto.OpenAccountRequest;
import com.corebank.account.repository.AccountStatusChangeRepository;
import com.corebank.account.service.AccountService;
import com.corebank.common.security.Actor;
import com.corebank.common.security.Actors;
import com.corebank.config.TestDeciders;
import com.corebank.customer.domain.IdentityLink;
import com.corebank.customer.domain.KycDecision;
import com.corebank.customer.domain.KycStatus;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.dto.IdentityLinkResponse;
import com.corebank.customer.dto.KycDecisionResponse;
import com.corebank.customer.repository.IdentityLinkRepository;
import com.corebank.customer.repository.KycDecisionRepository;
import com.corebank.customer.service.CustomerService;
import com.corebank.schedule.domain.ScheduleEventType;
import com.corebank.schedule.domain.ScheduleFrequency;
import com.corebank.schedule.domain.ScheduledTransferEvent;
import com.corebank.schedule.dto.CreateScheduledTransferRequest;
import com.corebank.schedule.dto.ScheduledTransferEventResponse;
import com.corebank.schedule.repository.ScheduledTransferEventRepository;
import com.corebank.schedule.service.ScheduledTransferService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

/**
 * Every history reads newest first, and "newest" has to mean the one written last even when two
 * share a timestamp. A resume and a cancel a moment apart can land on the same instant, and then
 * the timestamp alone leaves their order to the database: in practice, to the random ids.
 *
 * Each test writes {@value #TIED} entries at one instant and one older entry, the older one out of
 * place. Read back newest first, the tied entries must come in exact reverse order of writing, and
 * the older one after them; oldest first, the other way round.
 * Without a tiebreak the chance of that by luck is 1 in 10!, so these fail every time it is missing.
 */
@SpringBootTest
class HistoryOrderTest {

    private static final int TIED = 10;
    private static final Instant AT = Instant.parse("2026-03-01T09:30:00Z");
    private static final Actor STAFF = new Actor("teller-anil-subject", "anil");

    @Autowired private CustomerService customerService;
    @Autowired private AccountService accountService;
    @Autowired private ScheduledTransferService scheduledTransfers;
    @Autowired private KycDecisionRepository kycDecisions;
    @Autowired private AccountStatusChangeRepository statusChanges;
    @Autowired private IdentityLinkRepository identityLinks;
    @Autowired private ScheduledTransferEventRepository scheduleEvents;

    private UUID customer;
    private UUID account;

    @BeforeEach
    void setUp() {
        customer = newCustomer();
        customerService.updateKyc(customer, KycStatus.VERIFIED, null, TestDeciders.STAFF);
        account = accountService.open(new OpenAccountRequest(customer, AccountType.SAVINGS, "INR", BigDecimal.ZERO)).id();
    }

    private UUID newCustomer() {
        return customerService.create(new CreateCustomerRequest("Meera", "Iyer",
                "meera." + UUID.randomUUID() + "@example.com", null, LocalDate.of(1990, 1, 1))).id();
    }

    /** "#9", "#8" ... "#0", then "older": the order each history must come back in. */
    private static List<String> newestFirst() {
        List<String> expected = new java.util.ArrayList<>(
                IntStream.iterate(TIED - 1, i -> i >= 0, i -> i - 1).mapToObj(i -> "#" + i).toList());
        expected.add("older");
        return expected;
    }

    private static PageRequest firstPage() {
        return PageRequest.of(0, TIED + 5);
    }

    @Test
    @DisplayName("KYC decisions made at the same instant read back in the order they were made")
    void kycDecisionTies() {
        // One with no decisions yet: the setUp customer's verification would head the history.
        UUID customer = newCustomer();
        for (int i = 0; i < TIED; i++) {
            kycDecisions.save(new KycDecision(customer, KycStatus.PENDING, KycStatus.VERIFIED, TestDeciders.STAFF,
                    "#" + i, AT));
        }
        kycDecisions.save(new KycDecision(customer, KycStatus.PENDING, KycStatus.VERIFIED, TestDeciders.STAFF,
                "older", AT.minusSeconds(1)));

        assertThat(customerService.kycDecisions(customer, firstPage()).getContent())
                .extracting(KycDecisionResponse::reason).containsExactlyElementsOf(newestFirst());
    }

    @Test
    @DisplayName("account status changes at the same instant read back in the order they were made")
    void accountStatusChangeTies() {
        for (int i = 0; i < TIED; i++) {
            statusChanges.save(new AccountStatusChange(account, AccountStatus.ACTIVE, AccountStatus.FROZEN, STAFF,
                    "#" + i, AT));
        }
        statusChanges.save(new AccountStatusChange(account, AccountStatus.ACTIVE, AccountStatus.FROZEN, STAFF,
                "older", AT.minusSeconds(1)));

        assertThat(accountService.statusHistory(account, firstPage()).getContent())
                .extracting(AccountStatusChangeResponse::reason).containsExactlyElementsOf(newestFirst());
    }

    @Test
    @DisplayName("replayed oldest first, status changes at the same instant come in the order they were made")
    void accountStatusChangeTiesOldestFirst() {
        statusChanges.save(new AccountStatusChange(account, AccountStatus.ACTIVE, AccountStatus.FROZEN, STAFF,
                "older", AT.minusSeconds(1)));
        for (int i = 0; i < TIED; i++) {
            statusChanges.save(new AccountStatusChange(account, AccountStatus.ACTIVE, AccountStatus.FROZEN, STAFF,
                    "#" + i, AT));
        }

        // The interest run replays this to find the status on a past day; a freeze and an unfreeze
        // at one instant, replayed backwards, would leave the account frozen when it was not.
        List<String> oldestFirst = new java.util.ArrayList<>(newestFirst());
        java.util.Collections.reverse(oldestFirst);
        assertThat(statusChanges.findByAccountIdOrderByChangedAtAscSeqAsc(account))
                .extracting(AccountStatusChange::getReason).containsExactlyElementsOf(oldestFirst);
    }

    @Test
    @DisplayName("identity links made at the same instant read back in the order they were made")
    void identityLinkTies() {
        for (int i = 0; i < TIED; i++) {
            identityLinks.save(new IdentityLink(customer, null, "login-" + i, STAFF, "#" + i, AT));
        }
        identityLinks.save(new IdentityLink(customer, null, "login-older", STAFF, "older", AT.minusSeconds(1)));

        assertThat(customerService.identityLinks(customer, firstPage()).getContent())
                .extracting(IdentityLinkResponse::reason).containsExactlyElementsOf(newestFirst());
    }

    @Test
    @DisplayName("standing-order events at the same instant read back in the order they happened")
    void scheduledTransferEventTies() {
        UUID payee = accountService.open(new OpenAccountRequest(customer, AccountType.SAVINGS, "INR", BigDecimal.ZERO)).id();
        AtomicReference<UUID> created = new AtomicReference<>();
        Actors.runAs(STAFF, () -> created.set(scheduledTransfers.create(new CreateScheduledTransferRequest(account,
                payee, new BigDecimal("100.00"), "INR", "Rent", ScheduleFrequency.DAILY,
                LocalDate.now(ZoneOffset.UTC), null)).id()));
        UUID schedule = created.get();

        for (int i = 0; i < TIED; i++) {
            scheduleEvents.save(new ScheduledTransferEvent(schedule, ScheduleEventType.RESUMED, STAFF, "#" + i, AT));
        }
        scheduleEvents.save(new ScheduledTransferEvent(schedule, ScheduleEventType.SUSPENDED, STAFF, "older",
                AT.minusSeconds(1)));

        assertThat(scheduledTransfers.events(schedule, firstPage()).getContent())
                .extracting(ScheduledTransferEventResponse::reason).containsExactlyElementsOf(newestFirst());
    }
}
