package com.corebank.customer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

@Schema(description = "Onboards a new retail customer")
public record CreateCustomerRequest(
        @Schema(example = "Asha") @NotBlank @Size(max = 60) String firstName,
        @Schema(example = "Menon") @NotBlank @Size(max = 60) String lastName,
        @Schema(example = "asha.menon@example.com") @NotBlank @Email @Size(max = 160) String email,

        @Schema(example = "+919876543210")
        @Pattern(regexp = "^[+]?[0-9]{7,15}$", message = "must be 7 to 15 digits, optionally prefixed with +")
        String phone,

        @Schema(example = "1995-04-17", description = "Must be in the past; the customer must be at least 18")
        @NotNull @Past LocalDate dateOfBirth) {
}
