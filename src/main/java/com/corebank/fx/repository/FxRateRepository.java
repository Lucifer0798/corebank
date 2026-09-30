package com.corebank.fx.repository;

import com.corebank.fx.domain.FxRate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FxRateRepository extends JpaRepository<FxRate, UUID> {

    /**
     * The quote for one ordered pair. Ordered, not symmetric: INR/USD and USD/INR are separate
     * rows with separate spreads, because a bank's appetite for buying a currency is not its
     * appetite for selling it.
     */
    Optional<FxRate> findByBaseCurrencyAndQuoteCurrency(String baseCurrency, String quoteCurrency);
}
