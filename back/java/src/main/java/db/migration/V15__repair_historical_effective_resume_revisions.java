package db.migration;

import com.resumethinking.platform.resumes.ResumeTitleNormalizer;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Repairs historical logical resumes that were hidden when V10 selected
 * effective revisions.  Only a revision with a succeeded task, a matching
 * persisted result, and at least one persisted evidence row is eligible.
 *
 * <p>The migration is deliberately DML-only and transactional.  It never
 * turns an unmatched resume into an effective one, and it never silently
 * chooses between two active same-owner titles.</p>
 */
public final class V15__repair_historical_effective_resume_revisions extends BaseJavaMigration {
    private static final String SELECT_TARGETS = """
            SELECT id, owner_id, status, visibility_state
            FROM resumes
            WHERE effective_revision_id IS NULL
              AND pending_revision_id IS NULL
            ORDER BY id
            FOR UPDATE
            """.strip();

    private static final String SELECT_ACTIVE_TITLES = """
            SELECT resume.id AS resume_id,
                   resume.owner_id,
                   revision.title,
                   revision.state,
                   revision.resume_id AS revision_resume_id
            FROM resumes resume
            JOIN resume_revisions revision
              ON revision.id = resume.effective_revision_id
             AND revision.resume_id = resume.id
            WHERE resume.effective_revision_id IS NOT NULL
              AND resume.status = 0
              AND resume.visibility_state = 'ACTIVE'
            ORDER BY resume.owner_id, revision.title, resume.id
            FOR UPDATE
            """.strip();

    private static final String SELECT_CANDIDATES = """
            SELECT resume.id AS resume_id,
                   resume.owner_id,
                   revision.id AS revision_id,
                   revision.revision_no,
                   revision.title,
                   revision.source_type,
                   revision.parser_version,
                   revision.raw_content_ciphertext,
                   revision.raw_content_nonce,
                   revision.state AS revision_state,
                   task.id AS task_id,
                   task.created_at AS task_created_at,
                   COALESCE(task.publication_state, 'NOT_REQUESTED') AS publication_state,
                   result_row.completed_at
            FROM resumes resume
            JOIN resume_revisions revision
              ON revision.resume_id = resume.id
            JOIN analysis_tasks task
              ON task.resume_id = resume.id
             AND task.revision_id = revision.id
            JOIN analysis_results result_row
              ON result_row.task_id = task.id
             AND result_row.resume_id = resume.id
             AND result_row.revision_id = revision.id
            WHERE resume.effective_revision_id IS NULL
              AND resume.pending_revision_id IS NULL
              AND task.state = 'SUCCEEDED'
              AND COALESCE(task.publication_state, 'NOT_REQUESTED')
                    IN ('NOT_REQUESTED', 'PENDING', 'PUBLISHED')
              AND revision.state IN ('PENDING', 'EFFECTIVE', 'SUPERSEDED')
              /* A rejected task must not be selected, but a rejected task
                 for an older attempt of the same revision must not poison a
                 later successful attempt. */
              AND NOT EXISTS (
                  SELECT 1
                  FROM analysis_tasks rejected_task
                  WHERE rejected_task.id = task.id
                    AND rejected_task.publication_state = 'REJECTED_DUPLICATE_TITLE'
              )
              AND EXISTS (
                  SELECT 1
                  FROM analysis_evidence evidence
                  WHERE evidence.task_id = task.id
              )
            ORDER BY resume.id,
                     revision.revision_no DESC,
                     result_row.completed_at DESC,
                     task.created_at DESC,
                     task.id DESC
            FOR UPDATE
            """.strip();

    private static final String CLEAR_STALE_TITLE_KEY = """
            UPDATE resumes
            SET effective_title_key = NULL
            WHERE id = ?
              AND effective_revision_id IS NULL
            """.strip();

    private static final String PROMOTE_REVISION = """
            UPDATE resume_revisions
            SET state = CASE WHEN id = ? THEN 'EFFECTIVE' ELSE 'SUPERSEDED' END
            WHERE resume_id = ?
              AND (id = ? OR state = 'EFFECTIVE')
            """.strip();

    private static final String PUBLISH_TASK = """
            UPDATE analysis_tasks
            SET publication_state = 'PUBLISHED'
            WHERE id = ?
              AND state = 'SUCCEEDED'
              AND publication_state = 'PENDING'
            """.strip();

    private static final String RESTORE_PROJECTION = """
            UPDATE resumes
            SET effective_revision_id = ?,
                pending_revision_id = NULL,
                title = ?,
                source_type = ?,
                parser_version = ?,
                raw_content_ciphertext = ?,
                raw_content_nonce = ?,
                effective_title_key = CASE
                    WHEN status = 0 AND visibility_state = 'ACTIVE' THEN ?
                    ELSE NULL
                END
            WHERE id = ?
              AND effective_revision_id IS NULL
            """.strip();

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        Map<String, ResumeTarget> targets = loadTargets(connection);
        if (targets.isEmpty()) {
            return;
        }

        Map<OwnerTitleKey, String> occupiedTitles = loadActiveTitles(connection);
        Map<String, Candidate> selected = selectCandidates(connection, targets);
        validateCandidates(selected, targets, occupiedTitles);

        // Clear an inconsistent stale key before assigning any repaired
        // pointer.  The unique index then remains the final concurrency guard.
        clearStaleKeys(connection, targets.keySet());
        promoteRevisions(connection, selected.values());
        publishTasks(connection, selected.values());
        restoreProjection(connection, selected.values(), targets);
    }

    @Override
    public boolean canExecuteInTransaction() {
        return true;
    }

    private static Map<String, ResumeTarget> loadTargets(Connection connection) throws SQLException {
        Map<String, ResumeTarget> targets = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(SELECT_TARGETS)) {
            while (rows.next()) {
                String id = rows.getString("id");
                targets.put(id, new ResumeTarget(id, rows.getString("owner_id"),
                        rows.getInt("status"), rows.getString("visibility_state")));
            }
        }
        return targets;
    }

    private static Map<OwnerTitleKey, String> loadActiveTitles(Connection connection) throws SQLException {
        Map<OwnerTitleKey, String> occupied = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(SELECT_ACTIVE_TITLES)) {
            while (rows.next()) {
                String resumeId = rows.getString("resume_id");
                String revisionResumeId = rows.getString("revision_resume_id");
                if (!Objects.equals(resumeId, revisionResumeId)) {
                    throw new FlywayException(
                            "V15 blocked: an active effective revision belongs to another resume");
                }
                if (!"EFFECTIVE".equals(rows.getString("state"))) {
                    throw new FlywayException(
                            "V15 blocked: an active resume revision is not EFFECTIVE");
                }
                String key = normalizeTitle(rows.getString("title"), resumeId);
                OwnerTitleKey ownerTitle = new OwnerTitleKey(rows.getString("owner_id"), key);
                String previous = occupied.putIfAbsent(ownerTitle, resumeId);
                if (previous != null && !previous.equals(resumeId)) {
                    throw new FlywayException(
                            "V15 blocked: active same-owner resume titles conflict");
                }
            }
        }
        return occupied;
    }

    private static Map<String, Candidate> selectCandidates(
            Connection connection, Map<String, ResumeTarget> targets) throws SQLException {
        Map<String, Candidate> selected = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(SELECT_CANDIDATES)) {
            while (rows.next()) {
                String resumeId = rows.getString("resume_id");
                if (!targets.containsKey(resumeId)) {
                    throw new FlywayException(
                            "V15 blocked: a historical candidate is not in the locked target set");
                }
                Candidate candidate = new Candidate(
                        resumeId,
                        rows.getString("owner_id"),
                        rows.getString("revision_id"),
                        rows.getLong("revision_no"),
                        rows.getString("title"),
                        rows.getString("source_type"),
                        rows.getString("parser_version"),
                        rows.getBytes("raw_content_ciphertext"),
                        rows.getBytes("raw_content_nonce"),
                        rows.getString("revision_state"),
                        rows.getString("task_id"),
                        rows.getString("publication_state"),
                        timestamp(rows, "completed_at"),
                        timestamp(rows, "task_created_at"),
                        null);
                Candidate current = selected.get(resumeId);
                if (current == null || preferred(candidate, current)) {
                    selected.put(resumeId, candidate);
                }
            }
        }
        return selected;
    }

    private static void validateCandidates(Map<String, Candidate> selected,
                                           Map<String, ResumeTarget> targets,
                                           Map<OwnerTitleKey, String> occupiedTitles) {
        for (Map.Entry<String, Candidate> entry : selected.entrySet()) {
            Candidate candidate = entry.getValue();
            ResumeTarget target = targets.get(entry.getKey());
            if (target == null || !Objects.equals(target.ownerId(), candidate.ownerId())) {
                throw new FlywayException(
                        "V15 blocked: candidate ownership does not match its resume");
            }
            if (!List.of("TXT", "DOCX").contains(candidate.sourceType())
                    || candidate.ciphertext() == null
                    || candidate.ciphertext().length == 0
                    || (candidate.nonce() != null && candidate.nonce().length != 12)
                    || candidate.parserVersion() == null
                    || candidate.parserVersion().isBlank()
                    || !List.of("PENDING", "EFFECTIVE", "SUPERSEDED")
                    .contains(candidate.revisionState())) {
                throw new FlywayException(
                        "V15 blocked: a historical effective candidate is malformed");
            }

            String normalized = normalizeTitle(candidate.title(), candidate.resumeId());
            Candidate normalizedCandidate = candidate.withNormalizedTitle(normalized);
            entry.setValue(normalizedCandidate);

            if (isActive(target)) {
                OwnerTitleKey ownerTitle = new OwnerTitleKey(candidate.ownerId(), normalized);
                String previous = occupiedTitles.putIfAbsent(ownerTitle, candidate.resumeId());
                if (previous != null && !previous.equals(candidate.resumeId())) {
                    throw new FlywayException(
                            "V15 blocked: repairing an active resume would create a duplicate title");
                }
            }
        }
    }

    private static void clearStaleKeys(Connection connection, Iterable<String> resumeIds)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(CLEAR_STALE_TITLE_KEY)) {
            for (String resumeId : resumeIds) {
                update.setString(1, resumeId);
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    private static void promoteRevisions(Connection connection, Iterable<Candidate> candidates)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(PROMOTE_REVISION)) {
            for (Candidate candidate : candidates) {
                update.setString(1, candidate.revisionId());
                update.setString(2, candidate.resumeId());
                update.setString(3, candidate.revisionId());
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    private static void publishTasks(Connection connection, Iterable<Candidate> candidates)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(PUBLISH_TASK)) {
            for (Candidate candidate : candidates) {
                if (!"PENDING".equals(candidate.publicationState())) {
                    continue;
                }
                update.setString(1, candidate.taskId());
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    private static void restoreProjection(Connection connection, Iterable<Candidate> candidates,
                                          Map<String, ResumeTarget> targets) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(RESTORE_PROJECTION)) {
            for (Candidate candidate : candidates) {
                ResumeTarget target = targets.get(candidate.resumeId());
                update.setString(1, candidate.revisionId());
                update.setString(2, candidate.title());
                update.setString(3, candidate.sourceType());
                update.setString(4, candidate.parserVersion());
                update.setBytes(5, candidate.ciphertext());
                if (candidate.nonce() == null) {
                    update.setNull(6, Types.VARBINARY);
                } else {
                    update.setBytes(6, candidate.nonce());
                }
                if (isActive(target)) {
                    update.setString(7, candidate.normalizedTitle());
                } else {
                    update.setNull(7, Types.VARCHAR);
                }
                update.setString(8, candidate.resumeId());
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    private static boolean preferred(Candidate left, Candidate right) {
        int revision = Long.compare(left.revisionNo(), right.revisionNo());
        if (revision != 0) return revision > 0;
        int completed = left.completedAt().compareTo(right.completedAt());
        if (completed != 0) return completed > 0;
        int taskCreated = left.taskCreatedAt().compareTo(right.taskCreatedAt());
        if (taskCreated != 0) return taskCreated > 0;
        int task = left.taskId().compareTo(right.taskId());
        if (task != 0) return task > 0;
        return left.revisionId().compareTo(right.revisionId()) > 0;
    }

    private static boolean isActive(ResumeTarget target) {
        return target.status() == 0 && "ACTIVE".equals(target.visibilityState());
    }

    private static String normalizeTitle(String title, String resumeId) {
        try {
            return ResumeTitleNormalizer.normalize(title);
        } catch (IllegalArgumentException invalidTitle) {
            throw new FlywayException(
                    "V15 blocked: a historical title cannot be normalized for " + resumeId,
                    invalidTitle);
        }
    }

    private static Instant timestamp(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column);
        return value == null ? Instant.MIN : value.toInstant();
    }

    private record ResumeTarget(String id, String ownerId, int status, String visibilityState) {
    }

    private record OwnerTitleKey(String ownerId, String titleKey) {
    }

    private record Candidate(String resumeId, String ownerId, String revisionId, long revisionNo,
                             String title, String sourceType, String parserVersion,
                             byte[] ciphertext, byte[] nonce, String revisionState,
                             String taskId, String publicationState, Instant completedAt,
                             Instant taskCreatedAt, String normalizedTitle) {
        private Candidate withNormalizedTitle(String value) {
            return new Candidate(resumeId, ownerId, revisionId, revisionNo, title, sourceType,
                    parserVersion, ciphertext, nonce, revisionState, taskId, publicationState,
                    completedAt, taskCreatedAt, value);
        }
    }
}
