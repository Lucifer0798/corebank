package com.corebank.fx.web;

import com.corebank.common.validation.IsoCurrencyCode;
import com.corebank.common.validation.PositiveAmount;
import com.corebank.fx.dto.FxPositionReport;
import com.corebank.fx.service.FxPositionService;
import com.corebank.fx.service.FxRateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rates, so a conversion can be seen before it is committed to.
 *
 * <p>Read-only and deliberately so: quoting is not reserving. The rate returned here is the one a
 * transfer would use at this instant, and nothing holds it -- a transfer submitted a minute later
 * is converted at whatever is quoted then, and records that rate on the posting.
 */
@Validated
@Tag(name = "FX", description = "Exchange rates for cross-currency transfers")
@RestController
@RequestMapping("/api/v1/fx")
public class FxController {

    private final FxRateService fxRateService;
    private final FxPositionService fxPositionService;

    public FxController(FxRateService fxRateService, FxPositionService fxPositionService) {
        this.fxRateService = fxRateService;
        this.fxPositionService = fxPositionService;
    }

    @GetMapping("/quote")
    @PreAuthorize("hasAnyRole('TELLER', 'ADMIN', 'CUSTOMER')")
    @Operation(summary = "What an amount would convert to",
            description = "Returns both the mid rate and the rate after the bank's spread, so the margin "
                    + "is visible rather than folded into a single number.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Quoted"),
            @ApiResponse(responseCode = "422", description = "The bank does not quote that pair")
    })
    public FxRateService.Quote quote(
            @RequestParam @IsoCurrencyCode String from,
            @RequestParam @IsoCurrencyCode String to,
            @RequestParam @PositiveAmount BigDecimal amount) {

        return fxRateService.quote(from, to, amount);
    }

    @GetMapping("/position")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "The bank's own currency exposure",
            description = "Each position account in its own currency, valued at mid into the reporting "
                    + "currency, plus what the revaluation account currently carries the book at. The gap "
                    + "between the two is what a close would recognise. Admin-only: this is the bank's "
                    + "position, not a customer's.")
    public FxPositionReport position() {
        return fxPositionService.report();
    }

    @PostMapping("/revalue")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Mark the FX book to market",
            description = "Posts the change in the mark since the last run -- the delta, not the mark -- so "
                    + "running twice in a row is harmless and a missed close is caught up by the next one. "
                    + "Returns the posting's reference, or nothing when the mark has not moved. "
                    + "Deliberately an explicit action rather than a background job: a close is an "
                    + "operational decision about a point in time, not something to happen on a timer.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Revalued, or nothing to do"),
            @ApiResponse(responseCode = "422", description = "A position is held in a currency the bank no longer quotes")
    })
    public RevaluationResponse revalue() {
        return new RevaluationResponse(fxPositionService.revalue().orElse(null),
                fxPositionService.markCarried());
    }

    @Schema(description = "The result of a close")
    public record RevaluationResponse(
            @Schema(description = "The posting produced, or null when the mark had not moved")
            String reference,
            @Schema(description = "What the book is carried at afterwards")
            java.math.BigDecimal markCarried) {
    }
}
