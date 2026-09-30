package com.corebank.fx.web;

import com.corebank.common.validation.IsoCurrencyCode;
import com.corebank.common.validation.PositiveAmount;
import com.corebank.fx.service.FxRateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
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

    public FxController(FxRateService fxRateService) {
        this.fxRateService = fxRateService;
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
}
