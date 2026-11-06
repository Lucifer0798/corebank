package com.corebank.ledger;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

public record LedgerCheckReport(
        Instant checkedAt,

        @Schema(description = "True when every check found nothing")
        boolean clean,

        List<Result> checks) {

    public record Result(
            @Schema(example = "BALANCE_MATCHES_LEDGER")
            String check,

            @Schema(description = "What this check holds the books to, in words")
            String rule,

            List<Discrepancy> discrepancies,

            @Schema(description = "True when there are more than are listed here")
            boolean more) {
    }

    public record Discrepancy(
            @Schema(description = "The account number, customer number or currency that is wrong",
                    example = "100100000001")
            String subject,

            @Schema(description = "The figures that disagree", example = "balance 105.0000, ledger entries total 100.0000")
            String detail) {
    }
}
