package net.okocraft.kansokusha.common.storage;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
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

    private static void rejectTransactionControl(String sql) {
        var tokens = sqlTokens(sql);

        for (int index = 0; index < tokens.size(); index++) {
            var token = tokens.get(index);

            if ("BEGIN".equals(token) || "COMMIT".equals(token) || "ROLLBACK".equals(token)) {
                throw transactionControlNotAllowed(token);
            }

            if ("START".equals(token)
                && index + 1 < tokens.size()
                && "TRANSACTION".equals(tokens.get(index + 1))) {
                throw transactionControlNotAllowed("START TRANSACTION");
            }
        }
    }

    private static List<String> sqlTokens(String sql) {
        var tokens = new ArrayList<String>();
        var index = 0;

        while (index < sql.length()) {
            var current = sql.charAt(index);

            if (current == '\'' || current == '"') {
                index = skipQuoted(sql, index, current);
                continue;
            }

            if (current == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
                index = skipLineComment(sql, index + 2);
                continue;
            }

            if (current == '/' && index + 1 < sql.length() && sql.charAt(index + 1) == '*') {
                index = skipBlockComment(sql, index + 2);
                continue;
            }

            if (Character.isLetter(current) || current == '_') {
                var start = index++;
                while (index < sql.length()) {
                    var next = sql.charAt(index);
                    if (!Character.isLetterOrDigit(next) && next != '_' && next != '$') {
                        break;
                    }
                    index++;
                }

                tokens.add(sql.substring(start, index).toUpperCase(Locale.ROOT));
                continue;
            }

            index++;
        }

        return tokens;
    }

    private static int skipQuoted(String sql, int index, char quote) {
        index++;

        while (index < sql.length()) {
            if (sql.charAt(index) != quote) {
                index++;
                continue;
            }

            if (index + 1 < sql.length() && sql.charAt(index + 1) == quote) {
                index += 2;
                continue;
            }

            return index + 1;
        }

        return index;
    }

    private static int skipLineComment(String sql, int index) {
        while (index < sql.length() && sql.charAt(index) != '\n' && sql.charAt(index) != '\r') {
            index++;
        }
        return index;
    }

    private static int skipBlockComment(String sql, int index) {
        while (index + 1 < sql.length()) {
            if (sql.charAt(index) == '*' && sql.charAt(index + 1) == '/') {
                return index + 2;
            }
            index++;
        }
        return sql.length();
    }

    private static IllegalArgumentException transactionControlNotAllowed(String keyword) {
        return new IllegalArgumentException(
            "Migration SQL must not control transactions; found " + keyword
        );
    }
}
