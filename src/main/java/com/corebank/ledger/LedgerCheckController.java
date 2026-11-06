package com.corebank.ledger;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Ledger check", description = "Whether the books still add up")
@RequestMapping("/api/v1/admin/ledger-check")
@PreAuthorize("hasRole('ADMIN')")
public class LedgerCheckController {

    private final LedgerCheckService service;

    public LedgerCheckController(LedgerCheckService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Check that the books add up, now",
            description = "Read-only: it reports what disagrees and repairs nothing. Totals over whole tables, so "
                    + "not something to poll. The same check runs every night.")
    public LedgerCheckReport check() {
        return service.check();
    }
}
