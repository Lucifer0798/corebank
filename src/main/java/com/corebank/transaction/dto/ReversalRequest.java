package com.corebank.transaction.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Why a posting is being undone. Mandatory, and deliberately so: a reversal is the one operation
 * here that rewrites what the customer already saw on their statement, and "who decided this, and
 * on what grounds" is the first question anyone asks about one afterwards. The reason becomes the
 * reversing transaction's description, so it travels with the ledger rather than sitting in a log
 * that ages out.
 */
@Schema(description = "The justification recorded against a reversal")
public record ReversalRequest(
        @Schema(example = "Duplicate counter deposit keyed twice by branch 004")
        @NotBlank
        @Size(max = 255)
        String reason) {
}
