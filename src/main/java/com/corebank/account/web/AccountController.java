package com.corebank.account.web;

import com.corebank.account.domain.AccountStatus;
import com.corebank.account.dto.AccountResponse;
import com.corebank.account.dto.BalanceResponse;
import com.corebank.account.dto.OpenAccountRequest;
import com.corebank.account.service.AccountService;
import com.corebank.common.web.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

@Validated
@Tag(name = "Accounts", description = "Opening accounts, reading balances and account lifecycle")
@RestController
@RequestMapping("/api/v1")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping("/accounts")
    @PreAuthorize("hasAnyRole('TELLER', 'ADMIN')")
    @Operation(summary = "Open an account",
            description = "The customer must be ACTIVE and KYC-verified. Only CURRENT accounts may carry an overdraft.")
    public ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest request) {
        AccountResponse created = accountService.open(request);
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + created.id())).body(created);
    }

    @GetMapping("/accounts/{accountId}")
    @PreAuthorize("@accountSecurity.canReadAccount(authentication, #accountId)")
    @Operation(summary = "Fetch one account")
    public AccountResponse get(@PathVariable UUID accountId) {
        return accountService.get(accountId);
    }

    @GetMapping("/accounts/{accountId}/balance")
    @PreAuthorize("@accountSecurity.canReadAccount(authentication, #accountId)")
    @Operation(summary = "Read the current balance",
            description = "Available balance is the ledger balance plus any agreed overdraft.")
    public BalanceResponse balance(@PathVariable UUID accountId) {
        return BalanceResponse.from(accountService.require(accountId));
    }

    @GetMapping("/customers/{customerId}/accounts")
    @PreAuthorize("@accountSecurity.canReadCustomer(authentication, #customerId)")
    @Operation(summary = "List the accounts a customer holds")
    public PagedResponse<AccountResponse> listForCustomer(
            @PathVariable UUID customerId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PagedResponse.of(accountService.listForCustomer(
                customerId, PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "accountNumber"))));
    }

    @PostMapping("/accounts/{accountId}/freeze")
    @PreAuthorize("hasAnyRole('TELLER', 'ADMIN')")
    @Operation(summary = "Freeze an account", description = "A frozen account rejects every posting until it is unfrozen.")
    public AccountResponse freeze(@PathVariable UUID accountId) {
        return accountService.changeStatus(accountId, AccountStatus.FROZEN);
    }

    @PostMapping("/accounts/{accountId}/unfreeze")
    @PreAuthorize("hasAnyRole('TELLER', 'ADMIN')")
    @Operation(summary = "Return a frozen account to service")
    public AccountResponse unfreeze(@PathVariable UUID accountId) {
        return accountService.changeStatus(accountId, AccountStatus.ACTIVE);
    }

    @PostMapping("/accounts/{accountId}/close")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Close an account", description = "Refused unless the balance is exactly zero. Closing is final.")
    public AccountResponse close(@PathVariable UUID accountId) {
        return accountService.changeStatus(accountId, AccountStatus.CLOSED);
    }
}
