package com.corebank.schedule.web;

import com.corebank.common.web.PagedResponse;
import com.corebank.schedule.dto.CreateScheduledTransferRequest;
import com.corebank.schedule.dto.ScheduledTransferResponse;
import com.corebank.schedule.service.ScheduledTransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Standing instructions. Creating one needs no {@code Idempotency-Key}: unlike a transfer, a
 * duplicate request here creates a visible second mandate rather than silently moving money
 * twice, and it can be cancelled. The occurrences it goes on to post are each idempotent on their
 * own, under a key the mandate derives -- see {@code ScheduledTransfer.idempotencyKeyFor}.
 */
@Validated
@Tag(name = "Scheduled transfers", description = "Standing instructions to move money on a schedule")
@RestController
@RequestMapping("/api/v1")
public class ScheduledTransferController {

    private final ScheduledTransferService service;

    public ScheduledTransferController(ScheduledTransferService service) {
        this.service = service;
    }

    @PostMapping("/scheduled-transfers")
    @PreAuthorize("hasAnyRole('TELLER', 'ADMIN')")
    @Operation(summary = "Set up a standing instruction",
            description = "The first occurrence falls on startsOn, which must not be in the past.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Scheduled"),
            @ApiResponse(responseCode = "404", description = "No such account"),
            @ApiResponse(responseCode = "422", description = "Same account both sides, a start date in the past, "
                    + "an account that cannot take postings, or a window containing no occurrence")
    })
    public ResponseEntity<ScheduledTransferResponse> create(
            @Valid @RequestBody CreateScheduledTransferRequest request) {

        ScheduledTransferResponse created = service.create(request);
        return ResponseEntity
                .created(URI.create("/api/v1/scheduled-transfers/" + created.id()))
                .body(created);
    }

    @GetMapping("/scheduled-transfers/{id}")
    @PreAuthorize("hasAnyRole('TELLER', 'ADMIN')")
    @Operation(summary = "Fetch one standing instruction and how it has fared")
    public ScheduledTransferResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping("/accounts/{accountId}/scheduled-transfers")
    @PreAuthorize("@accountSecurity.canReadAccount(authentication, #accountId)")
    @Operation(summary = "Standing instructions against one account",
            description = "Both directions: instructions that pay out of this account and into it.")
    public PagedResponse<ScheduledTransferResponse> listForAccount(
            @PathVariable UUID accountId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

        return PagedResponse.of(service.listForAccount(accountId, PageRequest.of(page, size)));
    }

    @PostMapping("/scheduled-transfers/{id}/cancel")
    @PreAuthorize("hasAnyRole('TELLER', 'ADMIN')")
    @Operation(summary = "Stop a standing instruction",
            description = "Occurrences already posted stay posted; nothing further falls due.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cancelled"),
            @ApiResponse(responseCode = "422", description = "It had already stopped")
    })
    public ScheduledTransferResponse cancel(@PathVariable UUID id) {
        return service.cancel(id);
    }
}
