package db.migration;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class V14RecomputeUnicodeTitleKeysMigrationTest {
    @Test
    void successfulMigrationUsesLockedReadsAndTransactionalExistingColumnDmlOnly() throws Exception {
        Context context = mock(Context.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        PreparedStatement clearActiveKeys = mock(PreparedStatement.class);
        PreparedStatement updateRevisionKeys = mock(PreparedStatement.class);
        PreparedStatement updateActiveKeys = mock(PreparedStatement.class);
        ResultSet revisions = mock(ResultSet.class);
        ResultSet activeResumes = mock(ResultSet.class);

        when(context.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            return query.contains("FROM resume_revisions") ? revisions : activeResumes;
        });
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("SET effective_title_key = NULL")) return clearActiveKeys;
            if (sql.startsWith("UPDATE resume_revisions")) return updateRevisionKeys;
            return updateActiveKeys;
        });
        when(revisions.next()).thenReturn(true, false);
        when(revisions.getString("id")).thenReturn("revision001");
        when(revisions.getString("resume_id")).thenReturn("resume001");
        when(revisions.getString("title")).thenReturn("\u00a0\u0130\u3000");
        when(revisions.getString("state")).thenReturn("EFFECTIVE");
        when(activeResumes.next()).thenReturn(true, false);
        when(activeResumes.getString("id")).thenReturn("resume001");
        when(activeResumes.getString("owner_id")).thenReturn("user001");
        when(activeResumes.getString("effective_revision_id")).thenReturn("revision001");

        JavaMigration migration = loadMigration();
        migration.migrate(context);

        var querySql = org.mockito.ArgumentCaptor.forClass(String.class);
        var writeSql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(statement, times(2)).executeQuery(querySql.capture());
        verify(connection, atLeastOnce()).prepareStatement(writeSql.capture());
        List<String> writes = writeSql.getAllValues();

        assertAll(
                () -> assertThat(migration.canExecuteInTransaction()).isTrue(),
                () -> assertThat(querySql.getAllValues()).allMatch(sql -> sql.endsWith("FOR UPDATE")),
                () -> verify(statement, never()).execute(anyString()),
                () -> assertThat(writes).hasSize(3).allMatch(sql -> sql.startsWith("UPDATE ")),
                () -> assertThat(writes).noneMatch(sql -> sql.matches(
                        "(?is).*\\b(ALTER|CREATE|DROP|RENAME|TRUNCATE)\\b.*")),
                () -> verify(clearActiveKeys).setString(1, "resume001"),
                () -> verify(clearActiveKeys).setString(2, "revision001"),
                () -> verify(clearActiveKeys).addBatch(),
                () -> verify(clearActiveKeys).executeBatch(),
                () -> verify(updateRevisionKeys).setString(1, "i\u0307"),
                () -> verify(updateRevisionKeys).setString(2, "revision001"),
                () -> verify(updateRevisionKeys).addBatch(),
                () -> verify(updateRevisionKeys).executeBatch(),
                () -> verify(updateActiveKeys).setString(1, "i\u0307"),
                () -> verify(updateActiveKeys).setString(2, "resume001"),
                () -> verify(updateActiveKeys).setString(3, "revision001"),
                () -> verify(updateActiveKeys).addBatch(),
                () -> verify(updateActiveKeys).executeBatch());
    }

    @Test
    void backfillsHistoricalKeyFromTitleWithApplicationUnicodeCaseMapping() throws Exception {
        Context context = mock(Context.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        PreparedStatement clearActiveKeys = mock(PreparedStatement.class);
        PreparedStatement updateRevisionKeys = mock(PreparedStatement.class);
        PreparedStatement updateActiveKeys = mock(PreparedStatement.class);
        ResultSet revisions = mock(ResultSet.class);
        ResultSet activeResumes = mock(ResultSet.class);

        when(context.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("SET effective_title_key = NULL")) return clearActiveKeys;
            if (sql.startsWith("UPDATE resume_revisions")) return updateRevisionKeys;
            return updateActiveKeys;
        });
        when(statement.executeQuery(anyString())).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            return query.contains("FROM resume_revisions") ? revisions : activeResumes;
        });
        when(revisions.next()).thenReturn(true, false);
        when(revisions.getString("id")).thenReturn("revision001");
        when(revisions.getString("resume_id")).thenReturn("resume001");
        when(revisions.getString("title")).thenReturn("\u00a0\u0130\u3000");
        when(revisions.getString("title_key")).thenReturn("legacy-wrong-key");
        when(revisions.getString("state")).thenReturn("EFFECTIVE");
        when(activeResumes.next()).thenReturn(false);

        loadMigration().migrate(context);

        verify(revisions).getString("title");
        verify(revisions, never()).getString("title_key");
        verify(statement).executeQuery(
                "SELECT id, resume_id, title, state FROM resume_revisions ORDER BY id FOR UPDATE");
        verify(updateRevisionKeys).setString(1, "i\u0307");
        verify(updateRevisionKeys).setString(2, "revision001");
        verify(updateRevisionKeys).addBatch();
        verify(updateRevisionKeys).executeBatch();
        verify(statement, times(2)).executeQuery(anyString());
    }

    @Test
    void abortsUnicodeConflictsBeforeAnySchemaOrVisibleDataChange() throws Exception {
        Context context = mock(Context.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet revisions = mock(ResultSet.class);
        ResultSet activeResumes = mock(ResultSet.class);

        when(context.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            return query.contains("FROM resume_revisions")
                    ? revisions : activeResumes;
        });
        when(revisions.next()).thenReturn(true, true, false);
        when(revisions.getString("id")).thenReturn("revision001", "revision002");
        when(revisions.getString("resume_id")).thenReturn("resume001", "resume002");
        when(revisions.getString("title")).thenReturn("\u0130", "i\u0307");
        when(revisions.getString("state")).thenReturn("EFFECTIVE", "EFFECTIVE");
        when(activeResumes.next()).thenReturn(true, true, false);
        when(activeResumes.getString("id")).thenReturn("resume001", "resume002");
        when(activeResumes.getString("owner_id")).thenReturn("user001", "user001");
        when(activeResumes.getString("effective_revision_id"))
                .thenReturn("revision001", "revision002");

        assertThatThrownBy(() -> loadMigration().migrate(context))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("Unicode normalization merges active titles");

        verify(statement, never()).execute(anyString());
        verify(connection, never()).prepareStatement(anyString());
    }

    @ParameterizedTest
    @CsvSource({
            "resume999, EFFECTIVE, does not belong to its active resume",
            "resume001, PENDING, is not EFFECTIVE"
    })
    void abortsInvalidEffectiveRevisionBindingsBeforeAnySchemaOrVisibleDataChange(
            String revisionResumeId, String revisionState, String expectedMessage) throws Exception {
        Context context = mock(Context.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        PreparedStatement insertNormalizedKey = mock(PreparedStatement.class);
        ResultSet revisions = mock(ResultSet.class);
        ResultSet activeResumes = mock(ResultSet.class);

        when(context.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(anyString())).thenReturn(insertNormalizedKey);
        when(statement.executeQuery(anyString())).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            return query.contains("FROM resume_revisions") ? revisions : activeResumes;
        });
        when(revisions.next()).thenReturn(true, false);
        when(revisions.getString("id")).thenReturn("revision001");
        when(revisions.getString("resume_id")).thenReturn(revisionResumeId);
        when(revisions.getString("title")).thenReturn("Java Resume");
        when(revisions.getString("state")).thenReturn(revisionState);
        when(activeResumes.next()).thenReturn(true, false);
        when(activeResumes.getString("id")).thenReturn("resume001");
        when(activeResumes.getString("owner_id")).thenReturn("user001");
        when(activeResumes.getString("effective_revision_id")).thenReturn("revision001");

        assertThatThrownBy(() -> loadMigration().migrate(context))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining(expectedMessage);

        verify(statement, never()).execute(anyString());
        verify(connection, never()).prepareStatement(anyString());
    }

    private static JavaMigration loadMigration() {
        String className = "db.migration.V14__recompute_unicode_title_keys";
        try {
            Class<?> migrationType = Class.forName(className);
            assertThat(JavaMigration.class.isAssignableFrom(migrationType)).isTrue();
            return (JavaMigration) migrationType.getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException missingMigration) {
            fail("V14 must be a Java migration so historical titles use ResumeTitleNormalizer",
                    missingMigration);
            return null;
        } catch (ReflectiveOperationException reflectionFailure) {
            throw new AssertionError("Cannot instantiate V14 migration", reflectionFailure);
        }
    }
}
