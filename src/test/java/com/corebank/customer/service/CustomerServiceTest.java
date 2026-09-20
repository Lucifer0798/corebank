package com.corebank.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.corebank.common.SequenceNumberGenerator;
import com.corebank.common.exception.BusinessRuleException;
import com.corebank.common.exception.ConflictException;
import com.corebank.customer.domain.Customer;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.messaging.CustomerChangedEvent;
import com.corebank.customer.repository.CustomerRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The minimum-age rule, at the one date where it actually decides anything: the customer's
 * eighteenth birthday. {@code Period.between(...).getYears() < 18} is right, but the neighbouring
 * mistakes ({@code <=}, or counting days, or comparing years alone) all still pass a test written
 * with an obviously-underage or obviously-adult fixture. Only the boundary separates them.
 *
 * <p>This is the reason {@code CustomerService} takes a {@link Clock} rather than calling
 * {@code LocalDate.now()} -- it's what makes "today is exactly their birthday" something a test
 * can state rather than wait for.
 */
@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    /** Pinned so "eighteen years ago" is a fixed date rather than whenever the suite happens to run. */
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);

    @Mock
    private CustomerRepository customers;

    @Mock
    private SequenceNumberGenerator sequences;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private CustomerService customerService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        customerService = new CustomerService(customers, sequences, clock, eventPublisher);
    }

    private static CreateCustomerRequest bornOn(LocalDate dateOfBirth) {
        return new CreateCustomerRequest("Asha", "Menon", "asha.menon@example.com", null, dateOfBirth);
    }

    private void stubSuccessfulSave() {
        when(customers.existsByEmailIgnoreCase(any())).thenReturn(false);
        when(sequences.next(any())).thenReturn(1L);
        when(customers.save(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("someone turning eighteen today is old enough")
    void exactlyEighteenTodayIsAllowed() {
        stubSuccessfulSave();

        assertThatCode(() -> customerService.create(bornOn(TODAY.minusYears(18)))).doesNotThrowAnyException();
        verify(customers).save(any(Customer.class));
    }

    @Test
    @DisplayName("someone whose eighteenth birthday is tomorrow is not")
    void oneDayShortOfEighteenIsRejected() {
        when(customers.existsByEmailIgnoreCase(any())).thenReturn(false);

        assertThatThrownBy(() -> customerService.create(bornOn(TODAY.minusYears(18).plusDays(1))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("at least 18 years old")
                .extracting(ex -> ((BusinessRuleException) ex).getCode())
                .isEqualTo("UNDERAGE_CUSTOMER");
        verify(customers, never()).save(any(Customer.class));
    }

    @Test
    @DisplayName("someone who turned eighteen yesterday is old enough")
    void oneDayPastEighteenIsAllowed() {
        stubSuccessfulSave();

        assertThatCode(() -> customerService.create(bornOn(TODAY.minusYears(18).minusDays(1))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a new customer starts pending, active, and announces itself")
    void createdCustomerIsPendingAndPublished() {
        stubSuccessfulSave();

        var created = customerService.create(bornOn(LocalDate.of(1990, 1, 1)));

        assertThat(created.kycStatus().name()).isEqualTo("PENDING");
        assertThat(created.status().name()).isEqualTo("ACTIVE");
        assertThat(created.identityLinked()).isFalse();
        verify(eventPublisher).publishEvent(any(CustomerChangedEvent.class));
    }

    @Test
    @DisplayName("a taken email is refused before the age rule is even considered")
    void takenEmailIsRejected() {
        when(customers.existsByEmailIgnoreCase("asha.menon@example.com")).thenReturn(true);

        // Underage *and* duplicate: the email check has to be the one that fires, or the caller
        // gets told to come back in a year when the real problem is the address.
        assertThatThrownBy(() -> customerService.create(bornOn(TODAY.minusYears(2))))
                .isInstanceOf(ConflictException.class)
                .extracting(ex -> ((ConflictException) ex).getCode())
                .isEqualTo("EMAIL_TAKEN");
    }
}
