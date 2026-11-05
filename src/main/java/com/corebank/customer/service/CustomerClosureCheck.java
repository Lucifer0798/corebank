package com.corebank.customer.service;

import java.util.Optional;
import java.util.UUID;

/**
 * Something that would be left dangling if a customer closed now.
 *
 * <p>An interface rather than a repository call inside {@link CustomerService}, because the account
 * package depends on this one: the feature that owns the obligation says what it is, the same shape
 * as {@code AccountClosureCheck}.
 *
 * <p>Called with the customer's row already locked, which is what makes the answer still true at
 * commit: opening an account locks the same row first.
 */
public interface CustomerClosureCheck {

    /** A phrase such as "2 open accounts (1001, 1002)", or empty when nothing is outstanding. */
    Optional<String> outstandingFor(UUID customerId);
}
