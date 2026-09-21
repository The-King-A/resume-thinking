package com.resumethinking.platform.ids;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Verifies that the durable ID sequences cannot allocate an existing value.
 *
 * <p>Flyway completes before application runners execute, so this check runs
 * against the post-migration schema and fails startup before demo seed data or
 * user traffic can create a duplicate ID.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnBean(JdbcTemplate.class)
public final class ReadableIdSequenceStartupCheck implements ApplicationRunner {
    private static final List<SequenceSpec> SPECS = List.of(
            new SequenceSpec(BusinessIdType.USER, List.of(new IdSource("users", "id"))),
            new SequenceSpec(BusinessIdType.PROFILE, List.of(new IdSource("llm_profiles", "id"))),
            new SequenceSpec(BusinessIdType.RESUME, List.of(new IdSource("resumes", "id"))),
            new SequenceSpec(BusinessIdType.TASK, List.of(new IdSource("analysis_tasks", "id"))),
            new SequenceSpec(BusinessIdType.EVIDENCE, List.of(new IdSource("analysis_evidence", "id"))),
            new SequenceSpec(BusinessIdType.RESULT, List.of(new IdSource("analysis_results", "id"))),
            // Callback IDs are allocated from one shared sequence but are
            // persisted in both tables. Check both locations so a sequence
            // cannot be rewound behind a task that has not produced a receipt.
            new SequenceSpec(BusinessIdType.CALLBACK, List.of(
                    new IdSource("analysis_callback_receipts", "callback_id"),
                    new IdSource("analysis_tasks", "callback_id"))),
            new SequenceSpec(BusinessIdType.AUDIT, List.of(new IdSource("resume_recovery_audit", "id")))
    );

    private final JdbcTemplate jdbc;

    public ReadableIdSequenceStartupCheck(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc template must not be null");
    }

    @Override
    public void run(ApplicationArguments args) {
        check();
    }

    /** Run the check explicitly; useful for an operator smoke test and unit tests. */
    void check() {
        try {
            for (SequenceSpec spec : SPECS) {
                List<String> ids = spec.sources().stream()
                        .flatMap(source -> queryIds(source))
                        .toList();
                Long nextValue = jdbc.queryForObject(
                        "SELECT next_value FROM id_sequences WHERE sequence_name = ?",
                        Long.class, spec.type().prefix);
                validateSnapshot(spec.type(), ids, nextValue);
            }
        } catch (ReadableIdSequenceException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            // A missing table/column or an unavailable sequence row is a
            // deployment error, not a reason to start with unsafe writes.
            throw new ReadableIdSequenceException("ID_SEQUENCE_CHECK_FAILED", failure);
        }
    }

    private Stream<String> queryIds(IdSource source) {
        return jdbc.query(
                        "SELECT " + source.column() + " FROM " + source.table(),
                        (resultSet, rowNumber) -> resultSet.getString(1))
                .stream();
    }

    static void validateSnapshot(BusinessIdType type, List<String> ids, Long nextValue) {
        Objects.requireNonNull(type, "business id type must not be null");
        if (ids == null || nextValue == null || nextValue < 1) {
            throw invalid(type, "missing or invalid next_value");
        }
        long maximum = 0;
        for (String id : ids) {
            try {
                ReadableIdGenerator.validate(type, id);
                long suffix = Long.parseLong(id.substring(type.prefix.length()));
                maximum = Math.max(maximum, suffix);
            } catch (RuntimeException invalid) {
                throw invalid(type, "malformed existing ID");
            }
        }
        if (nextValue <= maximum) {
            throw invalid(type, "next_value is not greater than the existing maximum");
        }
    }

    private static ReadableIdSequenceException invalid(BusinessIdType type, String reason) {
        return new ReadableIdSequenceException("ID_SEQUENCE_CHECK_FAILED: " + type.prefix + " " + reason);
    }

    private record SequenceSpec(BusinessIdType type, List<IdSource> sources) {}

    private record IdSource(String table, String column) {}

    static final class ReadableIdSequenceException extends IllegalStateException {
        ReadableIdSequenceException(String message) {
            super(message);
        }

        ReadableIdSequenceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
