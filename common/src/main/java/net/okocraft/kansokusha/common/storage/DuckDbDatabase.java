package net.okocraft.kansokusha.common.storage;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;

@NotNullByDefault
public final class DuckDbDatabase implements AutoCloseable {

    private static final String DRIVER_CLASS = "org.duckdb.DuckDBDriver";

    private final Connection connection;

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

    @Override
    public void close() throws SQLException {
        this.connection.close();
    }
}
