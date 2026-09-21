package db.migration;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class V15RepairHistoricalEffectiveResumeRevisionsMigrationTest {
    @Test
    void repairsOnlyEvidenceBackedRowsAndKeepsHiddenTitleKeyNull() throws Exception {
        Context context = mock(Context.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        PreparedStatement clear = mock(PreparedStatement.class);
        PreparedStatement promote = mock(PreparedStatement.class);
        PreparedStatement publish = mock(PreparedStatement.class);
        PreparedStatement restore = mock(PreparedStatement.class);
        ResultSet targets = mock(ResultSet.class);
        ResultSet active = mock(ResultSet.class);
        ResultSet candidates = mock(ResultSet.class);

        when(context.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            if (query.contains("analysis_tasks")) return candidates;
            if (query.contains("effective_revision_id IS NOT NULL")) return active;
            return targets;
        });
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("effective_title_key = NULL")) return clear;
            if (sql.startsWith("UPDATE resume_revisions")) return promote;
            if (sql.startsWith("UPDATE analysis_tasks")) return publish;
            return restore;
        });

        when(targets.next()).thenReturn(true, true, false);
        when(targets.getString("id")).thenReturn("resume001", "resume002");
        when(targets.getString("owner_id")).thenReturn("user001", "user001");
        when(targets.getInt("status")).thenReturn(1, 0);
        when(targets.getString("visibility_state")).thenReturn("USER_SOFT_DELETED", "ACTIVE");
        when(active.next()).thenReturn(false);

        when(candidates.next()).thenReturn(true, true, false);
        when(candidates.getString("resume_id")).thenReturn("resume001", "resume002");
        when(candidates.getString("owner_id")).thenReturn("user001", "user001");
        when(candidates.getString("revision_id")).thenReturn("revision001", "revision002");
        when(candidates.getLong("revision_no")).thenReturn(1L, 1L);
        when(candidates.getString("title")).thenReturn("Hidden CV", "Visible CV");
        when(candidates.getString("source_type")).thenReturn("TXT", "DOCX");
        when(candidates.getString("parser_version")).thenReturn("v1", "v1");
        when(candidates.getBytes("raw_content_ciphertext"))
                .thenReturn(new byte[]{1}, new byte[]{2});
        when(candidates.getBytes("raw_content_nonce"))
                .thenReturn(new byte[12], new byte[12]);
        when(candidates.getString("revision_state")).thenReturn("SUPERSEDED", "EFFECTIVE");
        when(candidates.getString("task_id")).thenReturn("task001", "task002");
        when(candidates.getString("publication_state")).thenReturn("PENDING", "NOT_REQUESTED");
        when(candidates.getTimestamp("completed_at"))
                .thenReturn(Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
                        Timestamp.from(Instant.parse("2026-01-02T00:00:00Z")));
        when(candidates.getTimestamp("task_created_at"))
                .thenReturn(Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
                        Timestamp.from(Instant.parse("2026-01-02T00:00:00Z")));

        JavaMigration migration = loadMigration();
        migration.migrate(context);

        var querySql = org.mockito.ArgumentCaptor.forClass(String.class);
        var writeSql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(statement, times(3)).executeQuery(querySql.capture());
        verify(connection, atLeastOnce()).prepareStatement(writeSql.capture());
        List<String> writes = writeSql.getAllValues();

        assertThat(migration.canExecuteInTransaction()).isTrue();
        assertThat(querySql.getAllValues()).allMatch(sql -> sql.endsWith("FOR UPDATE"));
        assertThat(writes).hasSize(4).allMatch(sql -> sql.startsWith("UPDATE "));
        assertThat(writes).noneMatch(sql -> sql.matches(
                "(?is).*\\b(ALTER|CREATE|DROP|RENAME|TRUNCATE)\\b.*"));
        verify(clear, times(2)).addBatch();
        verify(clear).executeBatch();
        verify(promote, times(2)).addBatch();
        verify(promote).executeBatch();
        verify(publish).setString(1, "task001");
        verify(publish).executeBatch();
        verify(restore).setString(1, "revision001");
        verify(restore).setNull(7, Types.VARCHAR);
        verify(restore).setString(8, "resume001");
        verify(restore).setString(1, "revision002");
        verify(restore).setString(7, "visible cv");
        verify(restore).setString(8, "resume002");
        verify(restore).executeBatch();
    }

    @Test
    void rejectsAnActiveTitleCollisionBeforeAnyWrite() throws Exception {
        Context context = mock(Context.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet targets = mock(ResultSet.class);
        ResultSet active = mock(ResultSet.class);
        ResultSet candidates = mock(ResultSet.class);

        when(context.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            if (query.contains("analysis_tasks")) return candidates;
            if (query.contains("effective_revision_id IS NOT NULL")) return active;
            return targets;
        });

        when(targets.next()).thenReturn(true, false);
        when(targets.getString("id")).thenReturn("resume001");
        when(targets.getString("owner_id")).thenReturn("user001");
        when(targets.getInt("status")).thenReturn(0);
        when(targets.getString("visibility_state")).thenReturn("ACTIVE");

        when(active.next()).thenReturn(true, false);
        when(active.getString("resume_id")).thenReturn("resume002");
        when(active.getString("revision_resume_id")).thenReturn("resume002");
        when(active.getString("owner_id")).thenReturn("user001");
        when(active.getString("title")).thenReturn("Java CV");
        when(active.getString("state")).thenReturn("EFFECTIVE");

        when(candidates.next()).thenReturn(true, false);
        when(candidates.getString("resume_id")).thenReturn("resume001");
        when(candidates.getString("owner_id")).thenReturn("user001");
        when(candidates.getString("revision_id")).thenReturn("revision001");
        when(candidates.getLong("revision_no")).thenReturn(1L);
        when(candidates.getString("title")).thenReturn(" java cv ");
        when(candidates.getString("source_type")).thenReturn("TXT");
        when(candidates.getString("parser_version")).thenReturn("v1");
        when(candidates.getBytes("raw_content_ciphertext")).thenReturn(new byte[]{1});
        when(candidates.getBytes("raw_content_nonce")).thenReturn(new byte[12]);
        when(candidates.getString("revision_state")).thenReturn("SUPERSEDED");
        when(candidates.getString("task_id")).thenReturn("task001");
        when(candidates.getString("publication_state")).thenReturn("PUBLISHED");
        when(candidates.getTimestamp("completed_at"))
                .thenReturn(Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
        when(candidates.getTimestamp("task_created_at"))
                .thenReturn(Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));

        assertThatThrownBy(() -> loadMigration().migrate(context))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("duplicate title");

        verify(connection, never()).prepareStatement(anyString());
    }

    @Test
    void candidateQueryFailsClosedForRejectedPublicationAndUsesLockedDml() throws Exception {
        Context context = mock(Context.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet targets = mock(ResultSet.class);
        ResultSet active = mock(ResultSet.class);
        ResultSet candidates = mock(ResultSet.class);
        when(context.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            if (query.contains("analysis_tasks")) return candidates;
            if (query.contains("effective_revision_id IS NOT NULL")) return active;
            return targets;
        });
        when(targets.next()).thenReturn(true, false);
        when(targets.getString("id")).thenReturn("resume001");
        when(targets.getString("owner_id")).thenReturn("user001");
        when(targets.getInt("status")).thenReturn(1);
        when(targets.getString("visibility_state")).thenReturn("USER_SOFT_DELETED");
        when(active.next()).thenReturn(false);
        when(candidates.next()).thenReturn(false);

        loadMigration().migrate(context);

        var querySql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(statement, times(3)).executeQuery(querySql.capture());
        String candidateQuery = querySql.getAllValues().stream()
                .filter(query -> query.contains("analysis_tasks"))
                .findFirst()
                .orElseThrow();
        assertThat(candidateQuery).contains("NOT EXISTS", "REJECTED_DUPLICATE_TITLE");
    }

    private static JavaMigration loadMigration() {
        try {
            Class<?> type = Class.forName(
                    "db.migration.V15__repair_historical_effective_resume_revisions");
            assertThat(JavaMigration.class.isAssignableFrom(type)).isTrue();
            return (JavaMigration) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot instantiate V15 migration", failure);
        }
    }
}
