package com.corebank.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.sql.Statement;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A real (in-memory H2) DataSource, not a mock: the class under test exists specifically to
 * paper over a dialect difference in raw SQL, so a test that never runs any SQL would not be
 * checking the thing that actually varies.
 */
class SequenceNumberGeneratorTest {

    private SequenceNumberGenerator generator;

    @BeforeEach
    void setUp() throws SQLException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setUrl("jdbc:h2:mem:seq-test-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        try (var connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SEQUENCE demo_seq START WITH 1");
        }
        generator = new SequenceNumberGenerator(dataSource);
    }

    @Test
    @DisplayName("successive calls return successive values")
    void returnsIncreasingValues() {
        assertThat(generator.next("demo_seq")).isEqualTo(1L);
        assertThat(generator.next("demo_seq")).isEqualTo(2L);
        assertThat(generator.next("demo_seq")).isEqualTo(3L);
    }

    @Test
    @DisplayName("a sequence name that isn't a plain identifier is rejected before it reaches SQL")
    void rejectsInvalidSequenceNames() {
        assertThatThrownBy(() -> generator.next("demo_seq; DROP TABLE customer; --"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a valid sequence name");

        assertThatThrownBy(() -> generator.next("Demo_Seq"))
                .describedAs("uppercase is rejected too -- the allow-list is deliberately narrow, not just anti-injection")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an unknown sequence fails with a real database error, not silently")
    void unknownSequenceFails() {
        assertThatThrownBy(() -> generator.next("does_not_exist"))
                .isInstanceOf(RuntimeException.class);
    }
}
