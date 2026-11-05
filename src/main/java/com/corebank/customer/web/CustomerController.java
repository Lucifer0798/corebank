package com.corebank.customer.web;

import org.springframework.web.bind.annotation.PutMapping;
import com.corebank.customer.dto.RelinkIdentityRequest;
import com.corebank.customer.dto.IdentityLinkResponse;
import com.corebank.common.web.PagedResponse;
import com.corebank.customer.domain.KycDecider;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.domain.CustomerStatus;
import com.corebank.customer.dto.CustomerResponse;
import com.corebank.customer.dto.CustomerStatusChangeRequest;
import com.corebank.customer.dto.CustomerStatusChangeResponse;
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

    @PostMapping("/{customerId}/suspend")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Suspend a customer",
            description = "Money cannot leave their accounts and they cannot open new ones; payments in still "
                    + "arrive. Needs a reason, which is kept with who suspended them.")
    public CustomerResponse suspend(@PathVariable UUID customerId,
                                    @Valid @RequestBody(required = false) CustomerStatusChangeRequest request) {
        return customerService.changeStatus(customerId, CustomerStatus.SUSPENDED, reasonOf(request));
    }

    @PostMapping("/{customerId}/reinstate")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Lift a customer's suspension", description = "A reason is optional.")
    public CustomerResponse reinstate(@PathVariable UUID customerId,
                                      @Valid @RequestBody(required = false) CustomerStatusChangeRequest request) {
        return customerService.changeStatus(customerId, CustomerStatus.ACTIVE, reasonOf(request));
    }

    @PostMapping("/{customerId}/close")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Close a customer",
            description = "Refused while any of their accounts is open. Closing is final, and needs a reason.")
    public CustomerResponse close(@PathVariable UUID customerId,
                                  @Valid @RequestBody(required = false) CustomerStatusChangeRequest request) {
        return customerService.changeStatus(customerId, CustomerStatus.CLOSED, reasonOf(request));
    }

    @GetMapping("/{customerId}/status-changes")
    @PreAuthorize("hasAnyRole('TELLER', 'ADMIN')")
    @Operation(summary = "Who suspended, reinstated or closed this customer, and why -- newest first",
            description = "Staff only: the reason for a suspension can be a fraud report or a legal order.")
    public PagedResponse<CustomerStatusChangeResponse> statusChanges(
            @PathVariable UUID customerId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PagedResponse.of(customerService.statusHistory(customerId, PageRequest.of(page, size)));
    }

    private static String reasonOf(CustomerStatusChangeRequest request) {
        return request == null ? null : request.reason();
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
            description = "Required before that identity's tokens can read the customer's own accounts. A one-time "
                    + "step: a customer who already has a linked identity is refused with 409 CUSTOMER_ALREADY_LINKED, "
                    + "and replacing it is the admin-only PUT.")
    public CustomerResponse linkIdentity(@PathVariable UUID customerId,
                                         @Valid @RequestBody LinkIdentityRequest request) {
        return customerService.linkIdentity(customerId, request.keycloakSubject());
    }

    @PutMapping("/{customerId}/identity")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Replace the Keycloak identity linked to this customer",
            description = "Admin only, with a reason: the old login loses access to the customer's accounts and "
                    + "the new one gains it. Kept, with who did it, in the identity-link history.")
    public CustomerResponse relinkIdentity(@PathVariable UUID customerId,
                                           @Valid @RequestBody RelinkIdentityRequest request) {
        return customerService.relinkIdentity(customerId, request.keycloakSubject(), request.reason());
    }

    @GetMapping("/{customerId}/identity-links")
    @Operation(summary = "Every identity linked to this customer, who linked it and why -- newest first")
    public PagedResponse<IdentityLinkResponse> identityLinks(
            @PathVariable UUID customerId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PagedResponse.of(customerService.identityLinks(customerId, PageRequest.of(page, size)));
    }
}
