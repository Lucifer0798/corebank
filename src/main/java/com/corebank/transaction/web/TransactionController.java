package com.corebank.transaction.web;

import com.corebank.common.web.PagedResponse;
import com.corebank.idempotency.IdempotencyService;
import com.corebank.transaction.dto.AmountRequest;
import com.corebank.transaction.dto.StatementLineResponse;
import com.corebank.transaction.dto.TransactionResponse;
import com.corebank.transaction.dto.TransferRequest;
import com.corebank.transaction.service.TransactionService;
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
import java.time.Instant;
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
 * Money movement. Each endpoint requires an {@code Idempotency-Key}; the response carries
 * {@code Idempotency-Replayed} so a client can tell a genuine posting from a replayed one.
 */
@Validated
@Tag(name = "Transactions", description = "Deposits, withdrawals, transfers and statements")
@RestController
@RequestMapping("/api/v1")
public class TransactionController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final String REPLAYED_HEADER = "Idempotency-Replayed";

    private final TransactionService transactionService;
    private final IdempotencyService idempotencyService;

    public TransactionController(TransactionService transactionService, IdempotencyService idempotencyService) {
        this.transactionService = transactionService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping("/accounts/{accountId}/deposits")
    @PreAuthorize("hasAnyRole('TELLER', 'ADMIN')")
    @Operation(summary = "Deposit into an account",
            description = "Debits the bank cash account and credits the customer account in a single balanced posting.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Posted, or replayed from a previous identical request"),
            @ApiResponse(responseCode = "409", description = "The key was already used with a different body"),
            @ApiResponse(responseCode = "422", description = "The account is frozen, closed or in another currency")
    })
    public ResponseEntity<TransactionResponse> deposit(
            @PathVariable UUID accountId,
            @Parameter(description = "Unique per logical request; replaying it will not post twice", required = true)
            @RequestHeader(IDEMPOTENCY_HEADER) @NotBlank @Size(max = 80) String idempotencyKey,
            @Valid @RequestBody AmountRequest request) {

        return respond(idempotencyService.execute(
                "deposit:" + accountId, idempotencyKey, request, TransactionResponse.class,
                () -> transactionService.deposit(accountId, request, idempotencyKey)));
    }

    @PostMapping("/accounts/{accountId}/withdrawals")
    @PreAuthorize("hasAnyRole('TELLER', 'ADMIN')")
    @Operation(summary = "Withdraw from an account",
            description = "Refused with 422 when the available balance, including any overdraft, is too low.")
    public ResponseEntity<TransactionResponse> withdraw(
            @PathVariable UUID accountId,
            @RequestHeader(IDEMPOTENCY_HEADER) @NotBlank @Size(max = 80) String idempotencyKey,
            @Valid @RequestBody AmountRequest request) {

        return respond(idempotencyService.execute(
                "withdrawal:" + accountId, idempotencyKey, request, TransactionResponse.class,
                () -> transactionService.withdraw(accountId, request, idempotencyKey)));
    }

    @PostMapping("/transfers")
    @PreAuthorize("hasAnyRole('TELLER', 'ADMIN')")
    @Operation(summary = "Transfer between two accounts at this bank")
    public ResponseEntity<TransactionResponse> transfer(
            @RequestHeader(IDEMPOTENCY_HEADER) @NotBlank @Size(max = 80) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {

        return respond(idempotencyService.execute(
                "transfer", idempotencyKey, request, TransactionResponse.class,
                () -> transactionService.transfer(request, idempotencyKey)));
    }

    @GetMapping("/accounts/{accountId}/transactions")
    @PreAuthorize("@accountSecurity.canReadAccount(authentication, #accountId)")
    @Operation(summary = "Statement for one account, newest first",
            description = "Amounts are signed from this account's point of view: negative means the balance fell.")
    public PagedResponse<StatementLineResponse> statement(
            @PathVariable UUID accountId,
            @Parameter(description = "Inclusive lower bound, ISO-8601, e.g. 2025-04-01T00:00:00Z")
            @RequestParam(required = false) Instant from,
            @Parameter(description = "Inclusive upper bound, ISO-8601")
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

        return PagedResponse.of(
                transactionService.statement(accountId, from, to, PageRequest.of(page, size)));
    }

    @GetMapping("/transactions/{reference}")
    @PreAuthorize("hasAnyRole('TELLER', 'ADMIN')")
    @Operation(summary = "Fetch a transaction and both of its ledger legs")
    public TransactionResponse getByReference(@PathVariable String reference) {
        return transactionService.getByReference(reference);
    }

    private ResponseEntity<TransactionResponse> respond(IdempotencyService.Result<TransactionResponse> result) {
        TransactionResponse body = result.value();
        return ResponseEntity
                .created(URI.create("/api/v1/transactions/" + body.reference()))
                .header(REPLAYED_HEADER, Boolean.toString(result.replayed()))
                .body(body);
    }
}
