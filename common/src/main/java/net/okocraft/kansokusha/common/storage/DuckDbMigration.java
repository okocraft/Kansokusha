package net.okocraft.kansokusha.common.storage;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public record DuckDbMigration(int version, String name, List<String> statements) {

    public DuckDbMigration {
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }

        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }

        Objects.requireNonNull(statements, "statements");
        statements = List.copyOf(statements);
        if (statements.isEmpty()) {
            throw new IllegalArgumentException("statements must not be empty");
        }

        for (var statement : statements) {
            Objects.requireNonNull(statement, "statement");
            if (statement.isBlank()) {
                throw new IllegalArgumentException("statements must not contain blank SQL");
            }
        }
    }

    public static DuckDbMigration of(int version, String name, String... statements) {
        Objects.requireNonNull(statements, "statements");
        return new DuckDbMigration(version, name, List.of(statements));
    }

    public String checksum() {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 must be available", e);
        }

        for (var statement : this.statements) {
            var bytes = statement.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }

        return HexFormat.of().formatHex(digest.digest());
    }
}
