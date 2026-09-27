package com.corebank.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * Builds a complete {@link CoreBankProperties} for tests that construct a service by hand.
 *
 * <p>This exists because the alternative kept breaking. Those tests used to call the canonical
 * constructor positionally, passing {@code null} for every section they did not care about, so
 * every new configuration section broke every one of them -- three times over the life of this
 * project, each time in tests that had nothing to do with the feature being added. The compiler
 * caught it, so nothing shipped wrong, but the fix was always the same mechanical edit in the same
 * unrelated files.
 *
 * <p>Sections are filled with values that are valid and deliberately unremarkable. A test that
 * cares about one of them overrides it via a {@code with...} method rather than rebuilding the lot,
 * which also documents in one line what that test is actually about.
 */
public final class TestProperties {

    private TestProperties() {
    }

    public static CoreBankProperties defaults() {
        return new CoreBankProperties(
                new CoreBankProperties.Ledger("GL0000000001", "GL0000000002"),
                new CoreBankProperties.AccountSettings("1001"),
                new CoreBankProperties.Web(List.of("http://localhost:5173")),
                new CoreBankProperties.Search("http://localhost:9200",
                        Duration.ofSeconds(2), Duration.ofSeconds(5), 50),
                new CoreBankProperties.Outbox(50, Duration.ofSeconds(3)),
                new CoreBankProperties.ScheduledTransfers(50, 3),
                // High enough that a test not about limits never trips one by accident.
                new CoreBankProperties.Limits(new BigDecimal("1000000.00"), new BigDecimal("1000000.00")));
    }

    /** {@link #defaults()} with the outbox section replaced. */
    public static CoreBankProperties withOutbox(CoreBankProperties.Outbox outbox) {
        CoreBankProperties base = defaults();
        return new CoreBankProperties(base.ledger(), base.account(), base.web(), base.search(),
                outbox, base.scheduledTransfers(), base.limits());
    }

    /** {@link #defaults()} with the velocity limits replaced -- the point of most limit tests. */
    public static CoreBankProperties withLimits(String dailyDebitLimit, String singleTransactionLimit) {
        CoreBankProperties base = defaults();
        return new CoreBankProperties(base.ledger(), base.account(), base.web(), base.search(),
                base.outbox(), base.scheduledTransfers(),
                new CoreBankProperties.Limits(
                        new BigDecimal(dailyDebitLimit), new BigDecimal(singleTransactionLimit)));
    }
}
