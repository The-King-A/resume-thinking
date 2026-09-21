package com.resumethinking.platform.ids;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadableIdSequenceStartupCheckTest {
    @Test
    void acceptsAnEmptyTableAndTheFirstSequenceValue() {
        assertThatCode(() -> ReadableIdSequenceStartupCheck.validateSnapshot(
                BusinessIdType.USER, List.of(), 1L)).doesNotThrowAnyException();
    }

    @Test
    void acceptsASequenceStrictlyAfterTheLargestExistingId() {
        assertThatCode(() -> ReadableIdSequenceStartupCheck.validateSnapshot(
                BusinessIdType.USER, List.of("user001", "user009"), 10L)).doesNotThrowAnyException();
    }

    @Test
    void rejectsASequenceThatWouldReuseAnExistingId() {
        assertThatThrownBy(() -> ReadableIdSequenceStartupCheck.validateSnapshot(
                BusinessIdType.USER, List.of("user001", "user009"), 9L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("next_value");
    }

    @Test
    void rejectsMalformedLegacyIdsInsteadOfGuessingTheirSuffix() {
        assertThatThrownBy(() -> ReadableIdSequenceStartupCheck.validateSnapshot(
                BusinessIdType.RESUME, List.of("550e8400-e29b-41d4-a716-446655440000"), 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("malformed");
    }

    @Test
    void rejectsCallbackSequenceBehindATaskCallbackIdEvenWithoutAReceipt() {
        var jdbc = new SequenceJdbcTemplate();
        jdbc.idsByQuery.put("SELECT callback_id FROM analysis_tasks", List.of("callback007"));
        jdbc.nextValues.put("callback", 7L);

        assertThatThrownBy(() -> new ReadableIdSequenceStartupCheck(jdbc).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("callback")
                .hasMessageContaining("next_value");
    }

    private static final class SequenceJdbcTemplate extends JdbcTemplate {
        private final Map<String, List<String>> idsByQuery = new HashMap<>();
        private final Map<String, Long> nextValues = new HashMap<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, RowMapper<T> rowMapper) {
            return (List<T>) idsByQuery.getOrDefault(sql, List.of());
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            String sequenceName = (String) args[0];
            return (T) nextValues.getOrDefault(sequenceName, 1L);
        }
    }
}
