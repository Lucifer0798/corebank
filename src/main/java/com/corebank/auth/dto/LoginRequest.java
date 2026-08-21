package com.corebank.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Credentials exchanged for a bearer token")
public record LoginRequest(
        @Schema(example = "teller1")
        @NotBlank @Size(max = 64) String username,

        @Schema(example = "Teller#2025")
        @NotBlank @Size(max = 128) String password) {
}
