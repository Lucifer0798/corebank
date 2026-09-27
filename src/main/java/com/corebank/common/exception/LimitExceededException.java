package com.corebank.common.exception;

import java.math.BigDecimal;

/**
 * A posting refused by a velocity control rather than by a shortage of money.
 *
 * <p>Deliberately distinct from {@link InsufficientFundsException}, because the two mean opposite
 * things to whoever reads them. "You do not have the money" is about the account; "you have the
 * money but not today" is about the bank's own policy, and a customer told the first when the
 * second is true will go and check a balance that was never the problem.
 */
public class LimitExceededException extends BusinessRuleException {

    public LimitExceededException(String code, String message) {
        super(code, message);
    }

    public static LimitExceededException singleTransaction(BigDecimal requested, BigDecimal limit) {
        return new LimitExceededException("TRANSACTION_LIMIT_EXCEEDED",
                "A single posting may not exceed " + limit + "; " + requested + " was requested");
    }

    public static LimitExceededException daily(BigDecimal alreadyDebited, BigDecimal requested, BigDecimal limit) {
        // The remaining allowance is the one number the caller can act on, so it is stated rather
        // than left to be worked out from the other three.
        return new LimitExceededException("DAILY_LIMIT_EXCEEDED",
                "This account has already had " + alreadyDebited + " debited today against a limit of "
                        + limit + "; " + requested + " was requested and at most "
                        + limit.subtract(alreadyDebited).max(BigDecimal.ZERO) + " remains");
    }
}
