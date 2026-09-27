package com.corebank.transaction.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/**
 * Builds the customer-facing transaction reference, e.g. {@code TXN-20250417-K3P9WQ2M}.
 * The date prefix makes references easy to eyeball in support conversations; the random
 * suffix keeps them unguessable, and the unique constraint on the column is the real guard.
 */
@Component
public class ReferenceGenerator {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    // No vowels and no visually ambiguous characters, so references survive being read aloud.
    private static final char[] ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final int SUFFIX_LENGTH = 8;

    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public ReferenceGenerator(Clock clock) {
        this.clock = clock;
    }

    public String next() {
        return next("TXN");
    }

    /**
     * The same shape under a different prefix, so an authorisation hold reads as {@code HLD-...}
     * rather than borrowing the transaction vocabulary for something that is not a posting. The
     * alphabet and the length are what matter about this generator, and both are shared.
     */
    public String next(String prefix) {
        StringBuilder suffix = new StringBuilder(SUFFIX_LENGTH);
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            suffix.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return prefix + "-" + LocalDate.now(clock).format(DATE) + "-" + suffix;
    }
}
