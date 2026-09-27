package com.corebank.account.web;

import com.corebank.account.dto.CaptureHoldRequest;
import com.corebank.account.dto.HoldResponse;
import com.corebank.account.dto.PlaceHoldRequest;
import com.corebank.account.service.HoldService;
import com.corebank.common.web.PagedResponse;
import com.corebank.idempotency.IdempotencyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authorisation holds. Its own controller rather than more methods on {@code AccountController},
 * because a hold has its own lifecycle and its own reference, and only one of these five
 * endpoints is account-scoped at all.
 *
 * <p>Placing and capturing require an {@code Idempotency-Key} for the same reason money movement
 * does: a retried authorisation that reserved twice would be as wrong as a double payment, and
 * an account would be short by the amount with nothing in the ledger to explain it. Releasing
 * does not, because releasing an already-released hold is refused outright rather than repeated.
 */
@Validated
@Tag(name = "Holds", description = "Authorisation holds: reserve money now, move it later or not at all")
@RestController
@RequestMapping("/api/v1")
public class HoldController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final String REPLAYED_HEADER = "Idempotency-Replayed";

    private final HoldService holdService;
    private final IdempotencyService idempotencyService;

    public HoldController(HoldService holdService, IdempotencyService idempotencyService) {
        this.holdService = holdService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping("/accounts/{accountId}/holds")
    @PreAuthorize("hasAnyRole('TELLER', 'ADMIN')")
    @Operation(summary = "Place an authorisation hold",
            description = "Reserves money against the account without moving it. Nothing is posted to the "
                    + "ledger -- the bank's position has not changed -- but the amount stops counting towards "
                    + "the available balance until the hold is captured, released or expires.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Held, or replayed from a previous identical request"),
            @ApiResponse(responseCode = "409", description = "The key was already used with a different body"),
            @ApiResponse(responseCode = "422", description = "The available balance, net of other holds, is too low")
    })
    public ResponseEntity<HoldResponse> placeHold(
            @PathVariable UUID accountId,
            @Parameter(description = "Unique per logical request; replaying it will not reserve twice", required = true)
            @RequestHeader(IDEMPOTENCY_HEADER) @NotBlank @Size(max = 80) String idempotencyKey,
            @Valid @RequestBody PlaceHoldRequest request) {

        IdempotencyService.Result<HoldResponse> result = idempotencyService.execute(
                "hold:" + accountId, idempotencyKey, request, HoldResponse.class,
                () -> holdService.place(accountId, request));

        return ResponseEntity
                .created(URI.create("/api/v1/holds/" + result.value().reference()))
                .header(REPLAYED_HEADER, Boolean.toString(result.replayed()))
                .body(result.value());
    }

    @PostMapping("/holds/{reference}/capture")
    @PreAuthorize("hasAnyRole('TELLER', 'ADMIN')")
    @Operation(summary = "Capture a hold",
            description = "Turns the reservation into a real posting. Capturing less than was held is the "
                    + "ordinary case. Capturing more is allowed, but only the held portion is guaranteed -- "
                    + "the excess is checked against the available balance like any other withdrawal, and a "
                    + "refusal leaves the hold intact.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Captured"),
            @ApiResponse(responseCode = "404", description = "No hold with that reference"),
            @ApiResponse(responseCode = "409", description = "It was already captured, released or expired"),
            @ApiResponse(responseCode = "422", description = "Past its expiry, or the excess over the hold is unaffordable")
    })
    public HoldResponse captureHold(
            @PathVariable String reference,
            @RequestHeader(IDEMPOTENCY_HEADER) @NotBlank @Size(max = 80) String idempotencyKey,
            @Valid @RequestBody(required = false) CaptureHoldRequest request) {

        CaptureHoldRequest body = request == null ? new CaptureHoldRequest(null) : request;
        return idempotencyService.execute(
                "hold-capture:" + reference, idempotencyKey, body, HoldResponse.class,
                () -> holdService.capture(reference, body)).value();
    }

    @PostMapping("/holds/{reference}/release")
    @PreAuthorize("hasAnyRole('TELLER', 'ADMIN')")
    @Operation(summary = "Release a hold without capturing it",
            description = "The reserved money becomes spendable again immediately. No posting is produced, "
                    + "because none ever happened.")
    public HoldResponse releaseHold(@PathVariable String reference) {
        return holdService.release(reference);
    }

    @GetMapping("/holds/{reference}")
    @PreAuthorize("hasAnyRole('TELLER', 'ADMIN')")
    @Operation(summary = "Fetch one hold and what became of it")
    public HoldResponse getHold(@PathVariable String reference) {
        return holdService.get(reference);
    }

    @GetMapping("/accounts/{accountId}/holds")
    @PreAuthorize("@accountSecurity.canReadAccount(authentication, #accountId)")
    @Operation(summary = "Holds against one account, newest first",
            description = "Includes settled ones: what was captured and what merely expired is the "
                    + "difference between a merchant that bills and one that does not.")
    public PagedResponse<HoldResponse> listHolds(
            @PathVariable UUID accountId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

        return PagedResponse.of(holdService.listForAccount(accountId, PageRequest.of(page, size)));
    }
}
