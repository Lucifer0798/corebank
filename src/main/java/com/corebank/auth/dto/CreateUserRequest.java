package com.corebank.auth.dto;

import com.corebank.auth.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;

@Schema(description = "Creates a staff or self-service login. Administrators only.")
public record CreateUserRequest(
        @Schema(example = "teller2")
        @NotBlank @Size(min = 3, max = 64)
        @Pattern(regexp = "^[a-zA-Z0-9._-]+$", message = "may contain letters, digits, dot, underscore and hyphen only")
        String username,

        @Schema(description = "At least 10 characters. Stored only as a BCrypt hash.")
        @NotBlank @Size(min = 10, max = 128) String password,

        @Size(max = 120) String fullName,

        @NotEmpty Set<Role> roles,

        @Schema(description = "Links this login to a customer. Required for the CUSTOMER role.")
        UUID customerId) {
}
