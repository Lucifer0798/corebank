package com.corebank.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Runs one statement out of a migration file again, so that what a test checks is the SQL that
 * shipped rather than a copy of it.
 */
public final class MigrationSql {

    private MigrationSql() {
    }

    /** The statement in {@code migration} that begins with {@code prefix}, up to its semicolon. */
    public static String statement(String migration, String prefix) {
        try {
            String sql = new ClassPathResource("db/migration/" + migration).getContentAsString(StandardCharsets.UTF_8);
            int start = sql.indexOf(prefix);
            if (start < 0) {
                throw new IllegalArgumentException(migration + " has no statement starting " + prefix);
            }
            return sql.substring(start, sql.indexOf(';', start));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Runs {@code update} -- an UPDATE with no WHERE of its own -- against just {@code ids}, in a
     * session set to {@code timeZone}, which is put back afterwards: the connection goes back to a
     * pool other tests share.
     */
    public static void runFor(JdbcTemplate jdbc, String update, String timeZone, UUID... ids) {
        String in = String.join(", ", java.util.Collections.nCopies(ids.length, "?"));
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement session = connection.createStatement()) {
                session.execute("SET TIME ZONE '" + timeZone + "'");
            }
            try (PreparedStatement statement = connection.prepareStatement(update + " WHERE id IN (" + in + ")")) {
                for (int i = 0; i < ids.length; i++) {
                    statement.setObject(i + 1, ids[i]);
                }
                statement.executeUpdate();
            } finally {
                try (Statement session = connection.createStatement()) {
                    session.execute("SET TIME ZONE LOCAL");
                }
            }
            return null;
        });
    }
}
