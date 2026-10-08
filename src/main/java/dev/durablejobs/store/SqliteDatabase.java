package dev.durablejobs.store;

import dev.durablejobs.api.JobException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** One short-lived connection per transaction; no connection shared with adapter threads. */
public final class SqliteDatabase {
    private final Path path;

    public SqliteDatabase(Path requestedPath) {
        try {
            Path absolute = requestedPath.toAbsolutePath().normalize();
            if (absolute.getFileName() == null || Files.isSymbolicLink(absolute)) {
                throw new JobException("INVALID_DB_PATH", "Use a regular local database path.");
            }
            Files.createDirectories(absolute.getParent());
            path = absolute.getParent().toRealPath().resolve(absolute.getFileName());
            Class.forName("org.sqlite.JDBC");
        } catch (IOException | ClassNotFoundException e) {
            throw new JobException("STORAGE_UNAVAILABLE", "Cannot initialize local SQLite storage.", e);
        }
    }

    public Path path() { return path; }

    @FunctionalInterface public interface SqlWork<T> { T run(Connection connection) throws SQLException; }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA synchronous=FULL");
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    public <T> T read(SqlWork<T> work) {
        try (Connection connection = connect()) { return work.run(connection); }
        catch (SQLException e) { throw storage(e); }
    }

    public <T> T transaction(SqlWork<T> work) {
        try (Connection connection = connect(); Statement control = connection.createStatement()) {
            control.execute("BEGIN IMMEDIATE");
            try {
                T result = work.run(connection);
                control.execute("COMMIT");
                return result;
            } catch (SQLException | RuntimeException e) {
                try { control.execute("ROLLBACK"); }
                catch (SQLException rollback) { e.addSuppressed(rollback); }
                throw e;
            }
        } catch (SQLException e) { throw storage(e); }
    }

    public void initialize(String resource, int applicationId) {
        String schema;
        try (InputStream stream = SqliteDatabase.class.getResourceAsStream(resource)) {
            if (stream == null) throw new JobException("MISSING_SCHEMA", "Database schema resource is missing.");
            schema = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new JobException("MISSING_SCHEMA", "Cannot read database schema.", e); }
        transaction(connection -> {
            int version = pragma(connection, "user_version");
            int storedId = pragma(connection, "application_id");
            if (version == 0 && storedId == 0) {
                try (Statement statement = connection.createStatement();
                     ResultSet rows = statement.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
                    if (rows.next() && rows.getInt(1) != 0) {
                        throw new JobException("FOREIGN_DATABASE", "Refusing to initialize a nonempty foreign database.");
                    }
                }
                try (Statement statement = connection.createStatement()) {
                    // Schema resources contain only controlled DDL, with no semicolons inside strings.
                    for (String sql : schema.split(";")) if (!sql.isBlank()) statement.execute(sql);
                    statement.execute("PRAGMA application_id=" + applicationId);
                    statement.execute("PRAGMA user_version=1");
                }
            } else if (version != 1 || storedId != applicationId) {
                throw new JobException("UNSUPPORTED_DATABASE", "Unknown database kind or schema version.");
            }
            return null;
        });
        read(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("PRAGMA journal_mode=WAL")) {
                if (!rows.next() || !"wal".equalsIgnoreCase(rows.getString(1))) {
                    throw new JobException("UNSUPPORTED_DATABASE", "SQLite WAL is required on a local filesystem.");
                }
            }
            return null;
        });
    }

    private static int pragma(Connection connection, String name) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("PRAGMA " + name)) {
            if (!rows.next()) throw new SQLException("Missing database pragma.");
            return rows.getInt(1);
        }
    }

    private static JobException storage(SQLException cause) {
        return new JobException("STORAGE_ERROR", "SQLite operation failed; no success is assumed.", cause);
    }
}
