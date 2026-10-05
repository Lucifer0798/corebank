package com.corebank.common.exception;

/**
 * Why something was refused, in words that are safe to show a customer.
 *
 * <p>Exception messages are written for whoever is debugging: an insufficient-funds message names
 * the full account number and its available balance. That is right in a log and wrong on any
 * screen another customer can see -- a standing order is listed on the payee's account too. This
 * works from the stable {@link ApiException#getCode() code} instead, and says nothing a payee
 * should not learn: not the balance, and not which side's account was the problem.
 */
public final class FailureReason {

    private FailureReason() {
    }

    /** A clause that completes "...was not made: ". Anything unrecognised gets the generic one. */
    public static String describe(String code) {
        if (code == null) {
            return GENERIC;
        }
        return switch (code) {
            case "INSUFFICIENT_FUNDS" -> "there was not enough money in the account";
            case "DAILY_LIMIT_EXCEEDED" -> "it would have gone over the account's daily limit";
            case "TRANSACTION_LIMIT_EXCEEDED" -> "it is over the limit for a single payment";
            default -> GENERIC;
        };
    }

    /** The code a failure carries, or null for anything that is not one of ours. */
    public static String codeOf(Throwable failure) {
        return failure instanceof ApiException api ? api.getCode() : null;
    }

    private static final String GENERIC = "it could not be processed";
}
