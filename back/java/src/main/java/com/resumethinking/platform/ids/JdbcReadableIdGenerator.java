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
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("readable id generation requires an active write transaction");
        }

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
}
