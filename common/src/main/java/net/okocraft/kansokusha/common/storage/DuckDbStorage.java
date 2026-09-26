package net.okocraft.kansokusha.common.storage;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Resolves the platform-specific DuckDB JDBC artifact and loads the storage implementation
 * without adding DuckDB classes to the plugin class loader.
 */
@NotNullByDefault
public final class DuckDbStorage {

    private static final String DUCKDB_VERSION = "1.5.5.1";
    private static final String IMPLEMENTATION_CLASS =
        "net.okocraft.kansokusha.common.storage.duckdb.DuckDbStorageImpl";
    private static final String IMPLEMENTATION_PACKAGE =
        "net.okocraft.kansokusha.common.storage.duckdb.";
    private static final URI MAVEN_CENTRAL = URI.create(
        "https://repo.maven.apache.org/maven2/org/duckdb/duckdb_jdbc/" + DUCKDB_VERSION + "/"
    );
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private DuckDbStorage() {
    }

    public static Storage open(Path dataDirectory, Path filepath) throws IOException, SQLException {
        var dependency = prepareDependency(dataDirectory.resolve("libs"));
        var codeSource = DuckDbStorage.class.getProtectionDomain().getCodeSource();
        if (codeSource == null) {
            throw new IOException("Cannot locate the Kansokusha code source.");
        }

        var loader = new DuckDbClassLoader(
            new URL[]{codeSource.getLocation(), dependency.toUri().toURL()},
            DuckDbStorage.class.getClassLoader()
        );
        try {
            var implementation = Class.forName(IMPLEMENTATION_CLASS, true, loader);
            var open = implementation.getMethod("open", Path.class);
            var storage = (Storage) open.invoke(null, filepath);
            return new LoadedStorage(storage, loader);
        } catch (InvocationTargetException e) {
            closeAfterFailure(loader, e);
            var cause = e.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof SQLException sqlException) {
                throw sqlException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException("Failed to open DuckDB storage.", cause);
        } catch (ReflectiveOperationException | ClassCastException e) {
            closeAfterFailure(loader, e);
            throw new IOException("Failed to load DuckDB storage.", e);
        }
    }

    private static Path prepareDependency(Path libraryDirectory) throws IOException {
        Files.createDirectories(libraryDirectory);

        var classifier = platformClassifier();
        var fileName = "duckdb_jdbc-" + DUCKDB_VERSION + "-" + classifier + ".jar";
        var jar = libraryDirectory.resolve(fileName);
        var checksumFile = libraryDirectory.resolve(fileName + ".sha256");

        if (Files.isRegularFile(jar) && Files.isRegularFile(checksumFile)) {
            try {
                var cachedChecksum = parseChecksum(Files.readString(checksumFile));
                if (cachedChecksum.equals(sha256(jar))) {
                    return jar;
                }
            } catch (IllegalArgumentException ignored) {
                // Refresh the checksum below.
            }
        }

        var artifactUri = MAVEN_CENTRAL.resolve(fileName);
        var expectedChecksum = downloadChecksum(URI.create(artifactUri + ".sha256"));

        if (Files.isRegularFile(jar) && expectedChecksum.equals(sha256(jar))) {
            writeAtomically(checksumFile, expectedChecksum + System.lineSeparator());
            return jar;
        }

        var temporaryJar = Files.createTempFile(libraryDirectory, fileName + ".", ".tmp");
        try {
            download(artifactUri, temporaryJar);
            var actualChecksum = sha256(temporaryJar);
            if (!expectedChecksum.equals(actualChecksum)) {
                throw new IOException(
                    "Downloaded DuckDB JDBC artifact has an invalid SHA-256 checksum: expected "
                        + expectedChecksum + ", got " + actualChecksum
                );
            }
            moveAtomically(temporaryJar, jar);
        } finally {
            Files.deleteIfExists(temporaryJar);
        }

        writeAtomically(checksumFile, expectedChecksum + System.lineSeparator());
        return jar;
    }

    private static String platformClassifier() throws IOException {
        var osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        var architecture = normalizeArchitecture(System.getProperty("os.arch", ""));

        if (osName.contains("mac") || osName.contains("darwin")) {
            return "macos_universal";
        }

        if (osName.contains("windows")) {
            return "windows_" + architecture;
        }

        if (osName.contains("linux")) {
            return "linux_" + architecture + (isMusl(architecture) ? "_musl" : "");
        }

        throw new IOException(
            "Unsupported operating system for DuckDB JDBC: "
                + System.getProperty("os.name") + " / " + System.getProperty("os.arch")
        );
    }

    private static String normalizeArchitecture(String architecture) throws IOException {
        return switch (architecture.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64", "x64" -> "amd64";
            case "aarch64", "arm64" -> "arm64";
            default -> throw new IOException("Unsupported CPU architecture for DuckDB JDBC: " + architecture);
        };
    }

    private static boolean isMusl(String architecture) {
        return Files.exists(Path.of("/etc/alpine-release"))
            || Files.exists(Path.of("/lib/ld-musl-" + muslArchitecture(architecture) + ".so.1"))
            || Files.exists(Path.of("/usr/lib/ld-musl-" + muslArchitecture(architecture) + ".so.1"));
    }

    private static String muslArchitecture(String architecture) {
        return architecture.equals("amd64") ? "x86_64" : "aarch64";
    }

    private static String downloadChecksum(URI uri) throws IOException {
        var request = request(uri).build();
        try {
            var response = HTTP_CLIENT.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.US_ASCII)
            );
            requireSuccessful(response.statusCode(), uri);
            return parseChecksum(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading " + uri, e);
        }
    }

    private static void download(URI uri, Path target) throws IOException {
        var request = request(uri).build();
        try {
            var response = HTTP_CLIENT.send(
                request,
                HttpResponse.BodyHandlers.ofFile(
                    target,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
                )
            );
            requireSuccessful(response.statusCode(), uri);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading " + uri, e);
        }
    }

    private static HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMinutes(2))
            .header("User-Agent", "Kansokusha")
            .GET();
    }

    private static void requireSuccessful(int statusCode, URI uri) throws IOException {
        if (statusCode < HttpURLConnection.HTTP_OK || statusCode >= HttpURLConnection.HTTP_MULT_CHOICE) {
            throw new IOException("Failed to download " + uri + ": HTTP " + statusCode);
        }
    }

    private static String parseChecksum(String value) {
        var parts = value.trim().split("\\s+");
        if (parts.length == 0 || !parts[0].matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("Invalid SHA-256 checksum: " + value.trim());
        }
        return parts[0].toLowerCase(Locale.ROOT);
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }

        try (InputStream input = Files.newInputStream(file)) {
            var buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        var temporary = Files.createTempFile(target.getParent(), target.getFileName().toString() + ".", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.US_ASCII);
            moveAtomically(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void closeAfterFailure(URLClassLoader loader, Throwable failure) {
        try {
            loader.close();
        } catch (IOException e) {
            failure.addSuppressed(e);
        }
    }

    private static final class DuckDbClassLoader extends URLClassLoader {

        static {
            ClassLoader.registerAsParallelCapable();
        }

        private DuckDbClassLoader(URL[] urls, ClassLoader parent) {
            super("kansokusha-duckdb", urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                var loaded = findLoadedClass(name);
                if (loaded == null && isChildFirst(name)) {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException ignored) {
                        // Fall back to the parent below.
                    }
                }
                if (loaded == null) {
                    loaded = super.loadClass(name, false);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        private static boolean isChildFirst(String name) {
            return name.startsWith(IMPLEMENTATION_PACKAGE) || name.startsWith("org.duckdb.");
        }
    }

    private static final class LoadedStorage implements Storage {

        private final Storage delegate;
        private final URLClassLoader loader;

        private LoadedStorage(Storage delegate, URLClassLoader loader) {
            this.delegate = delegate;
            this.loader = loader;
        }

        @Override
        public void append(List<QueuedEvent> events) throws SQLException {
            this.delegate.append(events);
        }

        @Override
        public int deleteExpired(Instant now) throws SQLException {
            return this.delegate.deleteExpired(now);
        }

        @Override
        public void close() throws SQLException {
            SQLException failure = null;
            try {
                this.delegate.close();
            } catch (SQLException e) {
                failure = e;
            }

            try {
                this.loader.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = new SQLException("Failed to close the DuckDB class loader.", e);
                } else {
                    failure.addSuppressed(e);
                }
            }

            if (failure != null) {
                throw failure;
            }
        }
    }
}
