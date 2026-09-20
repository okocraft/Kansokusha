package net.okocraft.kansokusha.common.storage;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;

class DuckDbDatabaseTest {

    @Test
    void testOpenCloseAndReopen(@TempDir Path dir) throws Exception {
        var filepath = dir.resolve("nested").resolve("kansokusha.duckdb");

        Connection firstConnection;
        try (var database = DuckDbDatabase.open(filepath)) {
            firstConnection = database.connection();

            try (var statement = firstConnection.createStatement()) {
                statement.execute("CREATE TABLE connection_test (value INTEGER)");
                statement.execute("INSERT INTO connection_test VALUES (42)");
            }
        }

        Assertions.assertTrue(firstConnection.isClosed());
        Assertions.assertTrue(Files.exists(filepath));

        try (var database = DuckDbDatabase.open(filepath);
             var statement = database.connection().createStatement();
             var result = statement.executeQuery("SELECT value FROM connection_test")) {
            Assertions.assertTrue(result.next());
            Assertions.assertEquals(42, result.getInt(1));
            Assertions.assertFalse(result.next());
        }
    }

    @Test
    void testCloseReleasesDatabaseFile(@TempDir Path dir) throws Exception {
        var filepath = dir.resolve("kansokusha.duckdb");
        var database = DuckDbDatabase.open(filepath);
        var connection = database.connection();

        database.close();

        Assertions.assertTrue(connection.isClosed());
        Files.delete(filepath);
        Assertions.assertFalse(Files.exists(filepath));
    }
}
