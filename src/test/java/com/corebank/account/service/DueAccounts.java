package com.corebank.account.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Every account the interest run would reach, batch after batch, as {@link InterestRunner} pages
 * through them.
 *
 * <p>For tests asking whether one account is due. The first batch alone is the wrong question once
 * the shared test database holds more than a batch of due accounts: a due account can sort past it,
 * and "is not in the first batch" then holds whether or not it is due at all.
 */
public final class DueAccounts {

    private DueAccounts() {
    }

    public static List<UUID> toAccrue(InterestService interest, LocalDate day) {
        List<UUID> due = new ArrayList<>();
        for (List<UUID> batch = interest.findAccountsToAccrue(day, InterestService.FIRST); !batch.isEmpty();
                batch = interest.findAccountsToAccrue(day, batch.getLast())) {
            due.addAll(batch);
        }
        return due;
    }

    public static List<UUID> toCapitalise(InterestService interest, LocalDate day) {
        List<UUID> due = new ArrayList<>();
        for (List<UUID> batch = interest.findAccountsToCapitalise(day, InterestService.FIRST); !batch.isEmpty();
                batch = interest.findAccountsToCapitalise(day, batch.getLast())) {
            due.addAll(batch);
        }
        return due;
    }
}
