package com.corebank.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.corebank.schedule.service.ScheduledTransferService;
import com.corebank.schedule.service.ScheduledTransferService.DueOccurrence;
import com.corebank.transaction.dto.TransferRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * One mandate's trouble stays that mandate's. The runner works oldest first, so anything that made
 * a single mandate throw out of the loop would stop every mandate behind it -- on every tick, for
 * as long as that one stayed broken.
 */
@ExtendWith(MockitoExtension.class)
class ScheduledTransferRunnerIsolationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 1);

    @Mock
    private ScheduledTransferService service;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private ScheduledTransferRunner runner() {
        return new ScheduledTransferRunner(service, meters,
                Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));
    }

    private static DueOccurrence due(UUID id) {
        return new DueOccurrence(id, TODAY, "sched:" + id + ":" + TODAY,
                new TransferRequest(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), "INR", "Rent"));
    }

    @Test
    @DisplayName("a mandate whose bookkeeping throws does not stop the ones behind it")
    void oneBrokenMandateDoesNotBlockTheBatch() {
        UUID broken = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        when(service.findDue(TODAY)).thenReturn(List.of(broken, healthy));
        when(service.claim(broken, TODAY)).thenThrow(new IllegalStateException("row in a bad state"));
        when(service.claim(healthy, TODAY)).thenReturn(Optional.of(due(healthy)));

        assertThatCode(runner()::run).doesNotThrowAnyException();

        verify(service).markSuccess(eq(healthy), any());
        assertThat(meters.counter("corebank.scheduled.transfers", "outcome", "error").count()).isEqualTo(1);
    }
}
