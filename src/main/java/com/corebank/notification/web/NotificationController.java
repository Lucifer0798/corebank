package com.corebank.notification.web;

import com.corebank.common.web.PagedResponse;
import com.corebank.customer.service.CustomerService;
import com.corebank.notification.dto.NotificationResponse;
import com.corebank.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@Tag(name = "Notifications", description = "What customers have been told about money moving")
@RestController
@RequestMapping("/api/v1/customers")
public class NotificationController {

    private final NotificationService notificationService;
    private final CustomerService customerService;

    public NotificationController(NotificationService notificationService, CustomerService customerService) {
        this.notificationService = notificationService;
        this.customerService = customerService;
    }

    @GetMapping("/me/notifications")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "My notifications, newest first",
            description = "Resolved from the caller's own token, so there is no customer id to get wrong "
                    + "or to swap for someone else's.")
    public PagedResponse<NotificationResponse> mine(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

        UUID customerId = customerService.getBySubject(jwt.getSubject()).id();
        return PagedResponse.of(notificationService.forCustomer(customerId, PageRequest.of(page, size)));
    }

    @GetMapping("/{customerId}/notifications")
    @PreAuthorize("hasAnyRole('TELLER', 'ADMIN')")
    @Operation(summary = "A customer's notifications, newest first",
            description = "What the customer was told -- the first thing to check when one says they "
                    + "were never told about a payment.")
    public PagedResponse<NotificationResponse> forCustomer(
            @PathVariable UUID customerId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

        customerService.require(customerId);
        return PagedResponse.of(notificationService.forCustomer(customerId, PageRequest.of(page, size)));
    }
}
