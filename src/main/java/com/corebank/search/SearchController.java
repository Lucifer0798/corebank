package com.corebank.search;

import com.corebank.search.dto.CustomerSearchHit;
import com.corebank.search.dto.SearchResponse;
import com.corebank.search.dto.TransactionSearchHit;
import com.corebank.transaction.domain.TransactionType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bank-wide, cross-account search backed by OpenSearch -- the gap {@code GET
 * /accounts/{id}/transactions} and {@code GET /customers} deliberately don't cover, since those
 * are Postgres-backed, scoped to one account or unfiltered respectively. Staff-only, same as
 * {@code CustomerController}: search results carry the same customer PII a plain customer list
 * would.
 */
@Validated
@Tag(name = "Search", description = "Cross-account transaction and customer search (OpenSearch-backed)")
@RestController
@RequestMapping("/api/v1/search")
@PreAuthorize("hasAnyRole('TELLER', 'ADMIN')")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/transactions")
    @Operation(summary = "Search transactions bank-wide",
            description = "Free-text over the description, plus optional type/amount/date filters. "
                    + "Unlike the per-account statement endpoint, this searches across every account.")
    public SearchResponse<TransactionSearchHit> transactions(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @Parameter(description = "Inclusive lower bound, ISO-8601") @RequestParam(required = false) Instant from,
            @Parameter(description = "Inclusive upper bound, ISO-8601") @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return searchService.searchTransactions(q, type, minAmount, maxAmount, from, to, page, size);
    }

    @GetMapping("/customers")
    @Operation(summary = "Search customers by name, email or customer number")
    public SearchResponse<CustomerSearchHit> customers(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return searchService.searchCustomers(q, page, size);
    }
}
