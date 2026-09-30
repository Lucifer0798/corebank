package com.corebank.fx.service;

import com.corebank.common.exception.BusinessRuleException;
import com.corebank.fx.domain.FxRate;
import com.corebank.fx.repository.FxRateRepository;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Looks up the quote for a pair, and refuses clearly when the bank does not deal in it. */
@Service
public class FxRateService {

    private final FxRateRepository rates;

    public FxRateService(FxRateRepository rates) {
        this.rates = rates;
    }

    @Transactional(readOnly = true)
    public FxRate require(String from, String to) {
        if (from.equals(to)) {
            throw new IllegalArgumentException("No rate is needed to convert " + from + " to itself");
        }
        return rates.findByBaseCurrencyAndQuoteCurrency(from, to)
                .orElseThrow(() -> new BusinessRuleException("FX_RATE_UNAVAILABLE",
                        "This bank does not quote " + from + " against " + to));
    }

    /** A quote a caller can see before committing to the trade. */
    @Transactional(readOnly = true)
    public Quote quote(String from, String to, BigDecimal amount) {
        FxRate rate = require(from, to);
        return new Quote(from, to, amount, rate.convert(amount), rate.effectiveRate(),
                rate.getMidRate(), rate.getSpreadBps(), rate.getAsOf());
    }

    public record Quote(
            String fromCurrency,
            String toCurrency,
            BigDecimal fromAmount,
            BigDecimal toAmount,
            BigDecimal rateApplied,
            BigDecimal midRate,
            int spreadBps,
            java.time.Instant asOf) {
    }
}
