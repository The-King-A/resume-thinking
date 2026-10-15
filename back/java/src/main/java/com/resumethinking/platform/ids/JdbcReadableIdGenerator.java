package com.resumethinking.platform.ids;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

@Component
public final class JdbcReadableIdGenerator implements ReadableIdGenerator {
    private final JdbcTemplate jdbc;

    public JdbcReadableIdGenerator(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc template must not be null");
    }

    @Override
    public String next(BusinessIdType type) {
        Objects.requireNonNull(type, "business id type must not be null");
        requireWriteTransaction();

        jdbc.update("INSERT INTO id_sequences(sequence_name, next_value) VALUES (?, 1) "
                + "ON DUPLICATE KEY UPDATE sequence_name = VALUES(sequence_name)", type.prefix);
        Long sequence = jdbc.queryForObject(
                "SELECT next_value FROM id_sequences WHERE sequence_name = ? FOR UPDATE",
                Long.class, type.prefix);
        if (sequence == null || sequence < 1) {
            throw new IllegalStateException("invalid sequence value for " + type.prefix);
        }
        jdbc.update("UPDATE id_sequences SET next_value = ? WHERE sequence_name = ?",
                sequence + 1, type.prefix);
        return ReadableIdGenerator.format(type, sequence);
    }

    @Override
    public void ensureNextAtLeast(BusinessIdType type, long nextValue) {
        Objects.requireNonNull(type, "business id type must not be null");
        if (nextValue < 1) {
            throw new IllegalArgumentException("business id sequence must be at least 1");
        }
        requireWriteTransaction();
        // INSERT also repairs a missing row on a manually-created v2 schema;
        // the duplicate-key branch only raises the value and never rewinds it.
        jdbc.update("INSERT INTO id_sequences(sequence_name, next_value) VALUES (?, ?) "
                        + "ON DUPLICATE KEY UPDATE next_value = GREATEST(next_value, ?)",
                type.prefix, nextValue, nextValue);
    }

    private static void requireWriteTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("readable id generation requires an active write transaction");
        }
    }
}
