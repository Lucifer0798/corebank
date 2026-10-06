package com.corebank.account.service;

import java.util.Optional;
import java.util.UUID;

/**
 * Something that would be left dangling if an account closed now.
 *
 * <p>A zero balance was the only thing closure used to check, and it is not enough: an
 * authorisation hold can be outstanding on a current account sitting at zero inside its overdraft,
 * and standing instructions keep firing at an account whatever its status. Closing over either
 * breaks a promise somebody else was relying on -- a merchant's guaranteed capture, or a payer
 * whose standing order would now fail every time.
 *
 * <p>An interface rather than a list of repositories inside {@link AccountService}, so that the
 * features which own these obligations say what they are, and the account package does not have
 * to depend on every package that depends on it.
 *
 * <p>Called with the account's row already locked, which is what makes the answer still true at
 * commit: anything that adds an obligation to an account locks the same row first.
 */
public interface AccountClosureCheck {

    /** A phrase such as "2 standing instructions", or empty when nothing is outstanding. */
    Optional<String> outstandingFor(UUID accountId);
}
