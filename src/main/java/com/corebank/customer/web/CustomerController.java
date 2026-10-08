package com.corebank.customer.web;

import com.corebank.common.web.PagedResponse;
import com.corebank.customer.domain.KycDecider;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.dto.CustomerResponse;
import com.corebank.customer.dto.KycDecisionResponse;
import com.corebank.customer.dto.LinkIdentityRequest;
import com.corebank.customer.dto.UpdateKycRequest;
import com.corebank.customer.service.CustomerService;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@Tag(name = "Customers", description = "Onboarding and KYC")
@RestController
@RequestMapping("/api/v1/customers")
@PreAuthorize("hasAnyRole('TELLER', 'ADMIN')")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping
    @Operation(summary = "Onboard a customer", description = "The customer starts with KYC status PENDING and cannot hold accounts until verified.")
    public ResponseEntity<CustomerResponse> create(@Valid @RequestBody CreateCustomerRequest request) {
        CustomerResponse created = customerService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/customers/" + created.id())).body(created);
    }

    @GetMapping("/{customerId}")
    @Operation(summary = "Fetch one customer")
    public CustomerResponse get(@PathVariable UUID customerId) {
        return customerService.get(customerId);
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Fetch the customer record linked to the caller's own identity",
            description = "404 until staff link this identity to a customer via PATCH /{customerId}/identity.")
    public CustomerResponse me(@AuthenticationPrincipal Jwt jwt) {
        return customerService.getBySubject(jwt.getSubject());
    }

    @GetMapping
    @Operation(summary = "List customers, newest first")
    public PagedResponse<CustomerResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PagedResponse.of(customerService.list(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))));
    }

    @PatchMapping("/{customerId}/kyc")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Record a KYC decision",
            description = "Recorded with who made it -- taken from the caller's token, never from the body -- "
                    + "and why. A reason is required for anything but VERIFIED.")
    public CustomerResponse updateKyc(@PathVariable UUID customerId,
                                      @Valid @RequestBody UpdateKycRequest request,
                                      @AuthenticationPrincipal Jwt jwt) {
        KycDecider decider = new KycDecider(jwt.getSubject(), jwt.getClaimAsString("preferred_username"));
        return customerService.updateKyc(customerId, request.kycStatus(), request.reason(), decider);
    }

    @GetMapping("/{customerId}/kyc-decisions")
    @PreAuthorize("hasAnyRole('TELLER', 'ADMIN')")
    @Operation(summary = "A customer's KYC decisions, newest first",
            description = "Who decided, from what to what, and why. Starts when this history was introduced; "
                    + "earlier decisions were never recorded.")
    public PagedResponse<KycDecisionResponse> kycDecisions(
            @PathVariable UUID customerId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PagedResponse.of(customerService.kycDecisions(customerId, PageRequest.of(page, size)));
    }

    @PatchMapping("/{customerId}/identity")
    @Operation(summary = "Link a Keycloak identity to this customer",
            description = "Required before that identity's tokens can read the customer's own accounts.")
    public CustomerResponse linkIdentity(@PathVariable UUID customerId,
                                         @Valid @RequestBody LinkIdentityRequest request) {
        return customerService.linkIdentity(customerId, request.keycloakSubject());
    }
}
