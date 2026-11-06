package com.corebank.ledger;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/**
 * Whether the books still add up. Read-only: it reports what is wrong and repairs nothing, because
 * deciding which of two disagreeing figures is the true one is a person's job.
 *
 * <p>{@code account.balance} and {@code account.held_amount} are running totals, kept up to date
 * beside the ledger entries and holds they summarise. The only automatic check was
 * {@code assertBalanced}, on one transaction as it posts. Nothing ever compared the totals with what
 * they summarise, so a bug, a hand-written UPDATE or a bad migration would have shown the wrong
 * number on every screen with no error anywhere.
 *
 * <p>Plain SQL rather than the entities on purpose: this reads what is really in the tables, not what
 * the mappings it is checking say is there.
 *
 * <p>Each check is one statement, and that is what keeps it from crying wolf under load. A posting
 * updates the balance and writes its entries in one transaction, and a single statement sees one
 * snapshot, so it sees both or neither. No locks are taken and none are needed.
 */
@Service
public class LedgerCheckService {

    /** Enough to act on. A check past this says there are more rather than returning every row. */
    static final int MAX_LISTED = 50;

    /** One per check, always, and zero from startup: an alert has to be able to see the first problem. */
    static final String METRIC = "corebank.ledger.discrepancies";

    private static final String SIGNED_ENTRY = "CASE WHEN e.direction = a.normal_balance THEN e.amount ELSE -e.amount END";

    private static final List<Check> CHECKS = List.of(
            new Check("LEDGER_BALANCED",
                    "Debits equal credits across the whole ledger, in each currency",
                    """
                    SELECT a.currency,
                           SUM(CASE WHEN e.direction = 'DEBIT' THEN e.amount ELSE 0 END) AS debits,
                           SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE 0 END) AS credits
                      FROM ledger_entry e
                      JOIN account a ON a.id = e.account_id
                     GROUP BY a.currency
                    HAVING SUM(CASE WHEN e.direction = 'DEBIT' THEN e.amount ELSE 0 END)
                        <> SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE 0 END)
                     ORDER BY a.currency
                    """,
                    (row, n) -> new LedgerCheckReport.Discrepancy(row.getString(1),
                            "debits " + row.getBigDecimal(2).toPlainString()
                                    + ", credits " + row.getBigDecimal(3).toPlainString())),
            new Check("BALANCE_MATCHES_LEDGER",
                    "Every account's balance equals the sum of its ledger entries",
                    """
                    SELECT a.account_number, a.balance, COALESCE(SUM(%s), 0) AS ledger
                      FROM account a
                      LEFT JOIN ledger_entry e ON e.account_id = a.id
                     GROUP BY a.id, a.account_number, a.balance
                    HAVING a.balance <> COALESCE(SUM(%s), 0)
                     ORDER BY a.account_number
                    """.formatted(SIGNED_ENTRY, SIGNED_ENTRY),
                    (row, n) -> new LedgerCheckReport.Discrepancy(row.getString(1),
                            "balance " + row.getBigDecimal(2).toPlainString()
                                    + ", ledger entries total " + row.getBigDecimal(3).toPlainString())),
            new Check("HELD_MATCHES_HOLDS",
                    "Every account's held amount equals its active holds",
                    """
                    SELECT a.account_number, a.held_amount, COALESCE(SUM(h.amount), 0) AS active
                      FROM account a
                      LEFT JOIN account_hold h ON h.account_id = a.id AND h.status = 'ACTIVE'
                     GROUP BY a.id, a.account_number, a.held_amount
                    HAVING a.held_amount <> COALESCE(SUM(h.amount), 0)
                     ORDER BY a.account_number
                    """,
                    (row, n) -> new LedgerCheckReport.Discrepancy(row.getString(1),
                            "held " + row.getBigDecimal(2).toPlainString()
                                    + ", active holds total " + row.getBigDecimal(3).toPlainString())),
            new Check("CLOSED_ACCOUNTS_EMPTY",
                    "A closed account holds no money, no holds and no payable interest",
                    """
                    SELECT a.account_number, a.balance, a.held_amount, a.accrued_interest
                      FROM account a
                     WHERE a.status = 'CLOSED'
                       AND (a.balance <> 0 OR a.held_amount <> 0 OR a.accrued_interest >= 0.01)
                     ORDER BY a.account_number
                    """,
                    (row, n) -> new LedgerCheckReport.Discrepancy(row.getString(1),
                            "balance " + row.getBigDecimal(2).toPlainString()
                                    + ", held " + row.getBigDecimal(3).toPlainString()
                                    + ", interest accrued " + row.getBigDecimal(4).toPlainString())),
            new Check("CLOSED_CUSTOMERS_HAVE_NO_OPEN_ACCOUNTS",
                    "A closed customer holds no open account",
                    """
                    SELECT c.customer_number, a.account_number, a.status
                      FROM customer c
                      JOIN account a ON a.customer_id = c.id
                     WHERE c.status = 'CLOSED'
                       AND a.status <> 'CLOSED'
                     ORDER BY c.customer_number, a.account_number
                    """,
                    (row, n) -> new LedgerCheckReport.Discrepancy(row.getString(1),
                            "account " + row.getString(2) + " is " + row.getString(3).toLowerCase())));

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Map<String, AtomicLong> found = new HashMap<>();

    public LedgerCheckService(JdbcTemplate jdbc, Clock clock, MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        this.clock = clock;
        for (Check check : CHECKS) {
            AtomicLong count = new AtomicLong();
            found.put(check.code(), count);
            Gauge.builder(METRIC, count, AtomicLong::get)
                    .description("Problems the last ledger check found, capped at what it lists plus one")
                    .tag("check", check.code())
                    .register(meterRegistry);
        }
    }

    public LedgerCheckReport check() {
        List<LedgerCheckReport.Result> results = new ArrayList<>();
        for (Check check : CHECKS) {
            // One more than is listed, which is how a result knows there are others.
            List<LedgerCheckReport.Discrepancy> rows =
                    jdbc.query(check.sql() + " LIMIT " + (MAX_LISTED + 1), check.mapper());
            found.get(check.code()).set(rows.size());
            boolean more = rows.size() > MAX_LISTED;
            results.add(new LedgerCheckReport.Result(check.code(), check.rule(),
                    more ? rows.subList(0, MAX_LISTED) : rows, more));
        }
        return new LedgerCheckReport(Instant.now(clock),
                results.stream().allMatch(result -> result.discrepancies().isEmpty()), results);
    }

    private record Check(String code, String rule, String sql, RowMapper<LedgerCheckReport.Discrepancy> mapper) {
    }
}
