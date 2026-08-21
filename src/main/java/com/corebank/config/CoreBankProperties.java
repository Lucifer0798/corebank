package com.corebank.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Everything the platform needs configured per environment, validated at startup. */
@Validated
@ConfigurationProperties(prefix = "corebank")
public record CoreBankProperties(
        @Valid @NotNull Security security,
        @Valid @NotNull Ledger ledger,
        @Valid @NotNull AccountSettings account) {

    public record Security(
            @Valid @NotNull Jwt jwt,
            /** Password given to the bootstrap {@code admin} login the first time the database is empty. */
            @NotBlank String bootstrapAdminPassword) {
    }

    public record Jwt(
            /** HMAC signing key. HS256 needs at least 32 bytes, so short secrets are rejected here. */
            @NotBlank @Size(min = 32, message = "must be at least 32 characters") String secret,
            @NotBlank String issuer,
            @NotNull Duration accessTokenTtl) {
    }

    public record Ledger(
            @NotBlank String cashAccountNumber,
            @NotBlank String suspenseAccountNumber) {
    }

    public record AccountSettings(@NotBlank String numberPrefix) {
    }
}
