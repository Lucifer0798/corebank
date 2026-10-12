package com.corebank.customer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Replaces the sign-in identity already linked to a customer")
public record RelinkIdentityRequest(
        @Schema(description = "The new identity's Keycloak subject (its user ID)")
        @NotBlank @Size(max = 64) String keycloakSubject,

        @Schema(description = "Why the existing link is being replaced. Required.",
                example = "Customer's Keycloak account was recreated after a lockout")
        @Size(max = 500) String reason) {
}
