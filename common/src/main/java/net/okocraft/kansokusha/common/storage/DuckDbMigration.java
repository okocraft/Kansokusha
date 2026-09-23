package net.okocraft.kansokusha.common.storage;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public record DuckDbMigration(int version, String name, List<String> statements) {

    private static final Pattern LEADING_COMMENTS = Pattern.compile(
        "^(?:\\s+|--[^\\n]*(?:\\n|$)|/\\*.*?\\*/)*",
        Pattern.DOTALL
    );
    private static final Pattern TRANSACTION_CONTROL = Pattern.compile(
        "^(?:BEGIN|START|COMMIT|END|ROLLBACK|ABORT)\\b",
        Pattern.CASE_INSENSITIVE
    );

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
            rejectTransactionControl(statement);
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

    /**
     * Keeps the runner's per-migration transaction intact: each entry must be a single statement
     * and must not start with a transaction-control keyword.
     */
    private static void rejectTransactionControl(String statement) {
        var body = statement.strip();
        if (body.endsWith(";")) {
            body = body.substring(0, body.length() - 1);
        }
        if (body.indexOf(';') >= 0) {
            throw new IllegalArgumentException("Each migration statement must contain exactly one SQL statement");
        }

        var matcher = LEADING_COMMENTS.matcher(body);
        var start = matcher.lookingAt() ? matcher.end() : 0;
        if (TRANSACTION_CONTROL.matcher(body).region(start, body.length()).lookingAt()) {
            throw new IllegalArgumentException("Migration SQL must not control transactions: " + statement);
        }
    }
}
