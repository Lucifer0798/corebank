package com.corebank.customer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = """
        Links this customer to a Keycloak identity, so that identity's tokens can read the
        customer's own accounts. keycloakSubject is the `sub` claim of that identity's tokens --
        visible in the Keycloak admin console under the user's Details tab as "ID".""")
public record LinkIdentityRequest(
        @Schema(example = "00000000-0000-4000-8000-000000000003")
        @NotBlank @Size(max = 64) String keycloakSubject) {
}
