package com.corebank.customer.domain;

import java.util.Objects;

/**
 * Who made a KYC decision. Required, so there is no way to change a customer's KYC without saying
 * who did it -- a member of staff from their token, or a named system process.
 *
 * @param subject the token's {@code sub} claim, or {@code system:<process>} for a non-human decider
 * @param name    something a person reading the history recognises, such as the username
 */
public record KycDecider(String subject, String name) {

    public KycDecider {
        Objects.requireNonNull(subject, "a KYC decision needs a decider");
        if (subject.isBlank()) {
            throw new IllegalArgumentException("a KYC decision needs a decider");
        }
    }

    /** A process rather than a person, such as the dev-data seeder. */
    public static KycDecider system(String process) {
        return new KycDecider("system:" + process, process);
    }
}
