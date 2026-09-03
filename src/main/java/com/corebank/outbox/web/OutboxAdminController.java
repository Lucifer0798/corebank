package com.corebank.outbox.web;

import com.corebank.outbox.OutboxBackfillService;
import com.corebank.outbox.dto.ReplayResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@Tag(name = "Outbox admin",
        description = "Repairs event-publishing gaps by re-deriving events from the ledger and customer tables")
@RestController
@RequestMapping("/api/v1/admin/outbox")
@PreAuthorize("hasRole('ADMIN')")
public class OutboxAdminController {

    private final OutboxBackfillService backfillService;

    public OutboxAdminController(OutboxBackfillService backfillService) {
        this.backfillService = backfillService;
    }

    @PostMapping("/replay/transactions")
    @Operation(summary = "Re-enqueue transaction-posted events for a time window",
            description = "Re-derives TransactionPostedEvent from the ledger for every transaction posted in "
                    + "[since, until) and writes a fresh outbox row for each. Safe to run more than once over "
                    + "the same window: downstream consumers upsert by reference, not append.")
    public ReplayResponse replayTransactions(
            @RequestParam @NotNull Instant since,
            @RequestParam @NotNull Instant until) {
        return new ReplayResponse(backfillService.replayTransactions(since, until));
    }

    @PostMapping("/replay/customers")
    @Operation(summary = "Re-enqueue customer-changed events for a time window",
            description = "Re-derives CustomerChangedEvent from the customer table for every customer whose "
                    + "record changed in [since, until) and writes a fresh outbox row for each. Safe to run "
                    + "more than once over the same window: downstream consumers upsert by customer id, not append.")
    public ReplayResponse replayCustomers(
            @RequestParam @NotNull Instant since,
            @RequestParam @NotNull Instant until) {
        return new ReplayResponse(backfillService.replayCustomers(since, until));
    }
}
