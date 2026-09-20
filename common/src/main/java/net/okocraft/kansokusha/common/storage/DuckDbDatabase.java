package net.okocraft.kansokusha.common.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

public final class DuckDbDatabase implements AutoCloseable {

    private static final String DRIVER_CLASS = "org.duckdb.DuckDBDriver";

    private final Connection connection;
    private final ReentrantLock operationLock = new ReentrantLock();

    private DuckDbDatabase(Connection connection) {
        this.connection = connection;
    }

    public static DuckDbDatabase open(Path filepath) throws IOException, SQLException {
        Objects.requireNonNull(filepath, "filepath");
        loadDriver();

        var absoluteFilepath = filepath.toAbsolutePath().normalize();
        var parent = absoluteFilepath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        return new DuckDbDatabase(DriverManager.getConnection("jdbc:duckdb:" + absoluteFilepath));
    }

    private static void loadDriver() throws SQLException {
        try {
            Class.forName(DRIVER_CLASS);
        } catch (ClassNotFoundException e) {
            throw new SQLException("DuckDB JDBC driver is not available.", e);
        }
    }

    Connection connection() {
        return this.connection;
    }

    <T> T serialized(ConnectionOperation<T> operation) throws SQLException {
        Objects.requireNonNull(operation, "operation");
        this.operationLock.lock();

        try {
            return operation.execute(this.connection);
        } finally {
            this.operationLock.unlock();
        }
    }

    <T> T transaction(ConnectionOperation<T> operation) throws SQLException {
        Objects.requireNonNull(operation, "operation");

        return this.serialized(connection -> {
            if (!connection.getAutoCommit()) {
                throw new SQLException("Storage transaction requires a connection in auto-commit mode.");
            }

            executeTransactionStatement(connection, "BEGIN TRANSACTION");

            try {
                var result = operation.execute(connection);
                executeTransactionStatement(connection, "COMMIT");
                return result;
            } catch (SQLException | RuntimeException | Error failure) {
                try {
                    executeTransactionStatement(connection, "ROLLBACK");
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        });
    }

    private static void executeTransactionStatement(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @Override
    public void close() throws SQLException {
        this.serialized(connection -> {
            connection.close();
            return null;
        });
    }

    @FunctionalInterface
    interface ConnectionOperation<T> {

        T execute(Connection connection) throws SQLException;
    }
}
