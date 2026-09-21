package db.migration;

import com.resumethinking.platform.resumes.ResumeTitleNormalizer;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds historical title keys with the application Unicode rules.
 * Locked prechecks precede transactional DML; the existing unique index remains
 * the final guard against a concurrent same-owner title collision.
 */
public final class V14__recompute_unicode_title_keys extends BaseJavaMigration {
    private static final String SELECT_REVISIONS =
            "SELECT id, resume_id, title, state FROM resume_revisions ORDER BY id FOR UPDATE";
    private static final String SELECT_ACTIVE_RESUMES = """
            SELECT id, owner_id, effective_revision_id
            FROM resumes
            WHERE effective_revision_id IS NOT NULL
              AND status = 0
              AND visibility_state = 'ACTIVE'
            ORDER BY owner_id, effective_revision_id, id
            FOR UPDATE
            """.strip();
    private static final String CLEAR_ACTIVE_KEY = """
            UPDATE resumes
            SET effective_title_key = NULL
            WHERE id = ?
              AND effective_revision_id = ?
              AND status = 0
              AND visibility_state = 'ACTIVE'
            """.strip();
    private static final String UPDATE_REVISION_KEY =
            "UPDATE resume_revisions SET title_key = ? WHERE id = ?";
    private static final String UPDATE_ACTIVE_KEY = """
            UPDATE resumes
            SET effective_title_key = ?
            WHERE id = ?
              AND effective_revision_id = ?
              AND status = 0
              AND visibility_state = 'ACTIVE'
            """.strip();

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        Map<String, NormalizedRevision> normalizedRevisions;
        List<ActiveResume> activeResumes;
        try (Statement statement = connection.createStatement()) {
            normalizedRevisions = loadNormalizedRevisions(statement);
            activeResumes = loadAndValidateActiveResumes(statement, normalizedRevisions);
        }

        clearActiveKeys(connection, activeResumes);
        updateRevisionKeys(connection, normalizedRevisions);
        restoreActiveKeys(connection, activeResumes);
    }

    @Override
    public boolean canExecuteInTransaction() {
        return true;
    }

    private static Map<String, NormalizedRevision> loadNormalizedRevisions(Statement statement)
            throws Exception {
        Map<String, NormalizedRevision> normalizedRevisions = new LinkedHashMap<>();
        try (ResultSet revisions = statement.executeQuery(SELECT_REVISIONS)) {
            while (revisions.next()) {
                String revisionId = revisions.getString("id");
                String title = revisions.getString("title");
                try {
                    normalizedRevisions.put(revisionId, new NormalizedRevision(
                            revisions.getString("resume_id"),
                            ResumeTitleNormalizer.normalize(title),
                            revisions.getString("state")));
                } catch (IllegalArgumentException invalidTitle) {
                    throw new FlywayException(
                            "V14 blocked: a historical revision title cannot be normalized", invalidTitle);
                }
            }
        }
        return normalizedRevisions;
    }

    private static List<ActiveResume> loadAndValidateActiveResumes(
            Statement statement, Map<String, NormalizedRevision> normalizedRevisions)
            throws Exception {
        List<ActiveResume> activeRows = new ArrayList<>();
        Map<OwnerTitleKey, String> ownersAndTitles = new HashMap<>();
        try (ResultSet activeResumes = statement.executeQuery(SELECT_ACTIVE_RESUMES)) {
            while (activeResumes.next()) {
                String revisionId = activeResumes.getString("effective_revision_id");
                NormalizedRevision revision = normalizedRevisions.get(revisionId);
                if (revision == null) {
                    throw new FlywayException(
                            "V14 blocked: an active resume has no matching historical revision");
                }
                String resumeId = activeResumes.getString("id");
                if (!resumeId.equals(revision.resumeId())) {
                    throw new FlywayException(
                            "V14 blocked: effective revision does not belong to its active resume");
                }
                if (!"EFFECTIVE".equals(revision.state())) {
                    throw new FlywayException(
                            "V14 blocked: an active resume revision is not EFFECTIVE");
                }
                OwnerTitleKey ownerTitle = new OwnerTitleKey(
                        activeResumes.getString("owner_id"), revision.normalizedTitle());
                String previousResume = ownersAndTitles.putIfAbsent(ownerTitle, resumeId);
                if (previousResume != null) {
                    throw new FlywayException(
                            "V14 blocked: Unicode normalization merges active titles for one owner");
                }
                activeRows.add(new ActiveResume(resumeId, revisionId, revision.normalizedTitle()));
            }
        }
        return activeRows;
    }

    private static void clearActiveKeys(
            Connection connection, List<ActiveResume> activeResumes) throws Exception {
        try (PreparedStatement clear = connection.prepareStatement(CLEAR_ACTIVE_KEY)) {
            for (ActiveResume resume : activeResumes) {
                clear.setString(1, resume.resumeId());
                clear.setString(2, resume.revisionId());
                clear.addBatch();
            }
            clear.executeBatch();
        }
    }

    private static void updateRevisionKeys(
            Connection connection, Map<String, NormalizedRevision> normalizedRevisions)
            throws Exception {
        try (PreparedStatement update = connection.prepareStatement(UPDATE_REVISION_KEY)) {
            for (Map.Entry<String, NormalizedRevision> revision : normalizedRevisions.entrySet()) {
                update.setString(1, revision.getValue().normalizedTitle());
                update.setString(2, revision.getKey());
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    private static void restoreActiveKeys(
            Connection connection, List<ActiveResume> activeResumes) throws Exception {
        try (PreparedStatement update = connection.prepareStatement(UPDATE_ACTIVE_KEY)) {
            for (ActiveResume resume : activeResumes) {
                update.setString(1, resume.normalizedTitle());
                update.setString(2, resume.resumeId());
                update.setString(3, resume.revisionId());
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    private record OwnerTitleKey(String ownerId, String normalizedTitle) {
    }

    private record NormalizedRevision(String resumeId, String normalizedTitle, String state) {
    }

    private record ActiveResume(String resumeId, String revisionId, String normalizedTitle) {
    }
}
