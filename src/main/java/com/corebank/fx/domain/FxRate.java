package com.corebank.fx.domain;

import com.corebank.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

/**
 * One quoted pair: a unit of {@code baseCurrency} buys {@code midRate} of {@code quoteCurrency}.
 *
 * <p>The mid rate and the bank's margin are stored apart on purpose. A rate already net of spread
 * cannot be checked against any published source afterwards, so "what the market said" and "what
 * we charged" would become the same unverifiable number. Kept separate, a customer query about a
 * conversion has an answer with two halves.
 */
@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "fx_rate")
public class FxRate extends AuditableEntity {

    /** Basis points in a whole. A spread of 50 bps is half a percent. */
    private static final BigDecimal BPS_IN_ONE = new BigDecimal("10000");

    /**
     * Working scale for the rate arithmetic -- wider than both the stored rate and the money
     * scale, so that applying a spread and then converting rounds once, at the end, rather than
     * twice with the intermediate error carried forward.
     */
    private static final int WORKING_SCALE = 12;

    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "base_currency", nullable = false, length = 3, updatable = false)
    private String baseCurrency;

    @Column(name = "quote_currency", nullable = false, length = 3, updatable = false)
    private String quoteCurrency;

    @Column(name = "mid_rate", nullable = false, precision = 19, scale = 8)
    private BigDecimal midRate;

    @Column(name = "spread_bps", nullable = false)
    private int spreadBps;

    @Column(name = "as_of", nullable = false)
    private Instant asOf;

    /**
     * The rate the customer actually gets: the mid rate less the bank's margin.
     *
     * <p>Always <em>less</em>, in whichever direction the trade runs, because the spread is the
     * bank's and the customer is on the other side of it. Taking it off the rate rather than off
     * the converted amount keeps a single number that can be quoted, recorded on the posting, and
     * checked afterwards.
     */
    public BigDecimal effectiveRate() {
        BigDecimal margin = BigDecimal.valueOf(spreadBps).divide(BPS_IN_ONE, WORKING_SCALE, RoundingMode.HALF_UP);
        return midRate.multiply(BigDecimal.ONE.subtract(margin))
                .setScale(8, RoundingMode.HALF_UP);
    }

    /**
     * Converts an amount of the base currency into the quote currency at {@link #effectiveRate()},
     * rounded to the money scale.
     *
     * <p>Rounded HALF_UP rather than down: unlike accrued interest, there is nothing to carry here
     * -- the conversion happens once and the remainder has nowhere to live -- so the fair rule is
     * the one that does not systematically favour either side.
     */
    public BigDecimal convert(BigDecimal amount) {
        return amount.multiply(effectiveRate()).setScale(com.corebank.common.Money.SCALE, RoundingMode.HALF_UP);
    }
}
