package com.corebank.common;

import java.sql.SQLException;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reads the next value of a database sequence.
 *
 * <p>Sequence access is the one place where PostgreSQL and H2 disagree on syntax, so the
 * dialect is detected once at startup rather than guessed per call. Values are taken outside
 * the caller's transaction semantics -- a rolled-back request burns a number, which is
 * exactly how account numbering behaves in practice.
 */
@Component
public class SequenceNumberGenerator {

    /** Every caller today passes a hardcoded constant; this is a defensive floor underneath
     * that, not a real input boundary -- a plain identifier is all a sequence name can validly
     * be, so anything else means the string interpolation below was handed something it never
     * should have been. */
    private static final Pattern VALID_SEQUENCE_NAME = Pattern.compile("^[a-z_][a-z0-9_]*$");

    private final JdbcClient jdbcClient;
    private final String nextValueSql;

    public SequenceNumberGenerator(DataSource dataSource) {
        this.jdbcClient = JdbcClient.create(dataSource);
        this.nextValueSql = resolveDialect(dataSource);
    }

    public long next(String sequenceName) {
        if (!VALID_SEQUENCE_NAME.matcher(sequenceName).matches()) {
            throw new IllegalArgumentException("Not a valid sequence name: '" + sequenceName + "'");
        }
        Long value = jdbcClient.sql(nextValueSql.formatted(sequenceName))
                .query(Long.class)
                .single();
        if (value == null) {
            // Never happens in practice -- nextval()/next value for never yields SQL NULL --
            // but .single() is typed to allow it, so unboxing it blindly would trade a clear
            // failure here for a bare NullPointerException at the call site.
            throw new IllegalStateException("Sequence '" + sequenceName + "' produced no value");
        }
        return value;
    }

    private static String resolveDialect(DataSource dataSource) {
        String product;
        try (var connection = dataSource.getConnection()) {
            product = connection.getMetaData().getDatabaseProductName();
        } catch (SQLException ex) {
            throw new IllegalStateException("Unable to determine the database dialect", ex);
        }
        return "H2".equalsIgnoreCase(product)
                ? "select next value for %s"
                : "select nextval('%s')";
    }
}
