package com.corebank.fx.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Schema(description = "The bank's currency exposure, valued into the reporting currency")
public record FxPositionReport(

        @Schema(example = "INR")
        String reportingCurrency,

        List<Line> positions,

        @Schema(description = "What the whole book is worth now, at mid rates")
        BigDecimal markToMarket,

        @Schema(description = "What the revaluation account currently carries it at. The gap between "
                + "this and markToMarket is the unrecognised gain or loss a close would post.")
        BigDecimal markCarried,

        Instant asOf) {

    /** The position in one currency, and what it is worth. */
    public record Line(
            String currency,
            String accountNumber,

            @Schema(description = "The position itself, in its own currency. Negative means the bank is short.")
            BigDecimal balance,

            @Schema(description = "Units of the reporting currency per unit of this one, at mid. One for "
                    + "the reporting currency itself.")
            BigDecimal rateToReporting,

            BigDecimal valueInReportingCurrency) {
    }

    /** What a close would recognise. Stated rather than left to be subtracted. */
    public BigDecimal unrecognised() {
        return markToMarket.subtract(markCarried);
    }
}
