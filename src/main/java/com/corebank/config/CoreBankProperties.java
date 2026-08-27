package com.corebank.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Everything the platform needs configured per environment, validated at startup. */
@Validated
@ConfigurationProperties(prefix = "corebank")
public record CoreBankProperties(
        @Valid @NotNull Ledger ledger,
        @Valid @NotNull AccountSettings account,
        @Valid @NotNull Web web,
        @Valid @NotNull Search search) {

    public record Ledger(
            @NotBlank String cashAccountNumber,
            @NotBlank String suspenseAccountNumber) {
    }

    public record AccountSettings(@NotBlank String numberPrefix) {
    }

    /** Origins the browser-facing frontend is served from, for CORS. */
    public record Web(@NotEmpty List<String> allowedOrigins) {
    }

    public record Search(@NotBlank String opensearchUri) {
    }
}
