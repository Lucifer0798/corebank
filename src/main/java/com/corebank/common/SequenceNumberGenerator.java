package com.corebank.common;

import java.sql.SQLException;
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

    private final JdbcClient jdbcClient;
    private final String nextValueSql;

    public SequenceNumberGenerator(DataSource dataSource) {
        this.jdbcClient = JdbcClient.create(dataSource);
        this.nextValueSql = resolveDialect(dataSource);
    }

    public long next(String sequenceName) {
        return jdbcClient.sql(nextValueSql.formatted(sequenceName))
                .query(Long.class)
                .single();
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
