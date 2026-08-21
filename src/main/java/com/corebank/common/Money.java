package com.corebank.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money is held as {@link BigDecimal} with a fixed scale of 2 for presentation and
 * a scale of 4 in storage, which leaves headroom for interest and fee calculations
 * in later phases without ever falling back to binary floating point.
 */
public final class Money {

    public static final int SCALE = 2;

    /** The single currency Phase 1 books in. Multi-currency ledgers arrive with FX in a later phase. */
    public static final String BASE_CURRENCY = "INR";
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, RoundingMode.UNNECESSARY);

    private Money() {
    }

    /** Rescales an amount to the canonical scale, rejecting anything that would lose precision. */
    public static BigDecimal normalize(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        return amount.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    public static boolean isPositive(BigDecimal amount) {
        return amount != null && amount.compareTo(BigDecimal.ZERO) > 0;
    }

    public static BigDecimal orZero(BigDecimal amount) {
        return amount == null ? ZERO : normalize(amount);
    }
}
