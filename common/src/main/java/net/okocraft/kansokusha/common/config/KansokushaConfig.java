package net.okocraft.kansokusha.common.config;

import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNullByDefault;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validated contents of {@code config.yml}.
 */
@NotNullByDefault
public record KansokushaConfig(
    Optional<Key> serverKey,
    int queueCapacity,
    int batchSize,
    Duration flushInterval,
    Duration cleanupInterval,
    Duration archiveInterval,
    ZoneId searchTimeZone,
    Retention retention,
    DuckDb duckDb,
    OreVein oreVein
) {

    public KansokushaConfig(
        Optional<Key> serverKey,
        int queueCapacity,
        int batchSize,
        Duration flushInterval,
        Duration cleanupInterval,
        Duration archiveInterval,
        ZoneId searchTimeZone,
        Retention retention
    ) {
        this(serverKey, queueCapacity, batchSize, flushInterval, cleanupInterval, archiveInterval,
            searchTimeZone, retention, DuckDb.DEFAULT);
    }

    public KansokushaConfig(
        Optional<Key> serverKey,
        int queueCapacity,
        int batchSize,
        Duration flushInterval,
        Duration cleanupInterval,
        Duration archiveInterval,
        ZoneId searchTimeZone,
        Retention retention,
        DuckDb duckDb
    ) {
        this(serverKey, queueCapacity, batchSize, flushInterval, cleanupInterval, archiveInterval,
            searchTimeZone, retention, duckDb, OreVein.DISABLED);
    }

    private static final String FILENAME = "config.yml";
    private static final Pattern MEMORY_LIMIT = Pattern.compile("[0-9]+(\\.[0-9]+)? ?(B|KB|MB|GB|TB|KiB|MiB|GiB|TiB)");

    /**
     * Loads {@code config.yml} from the data directory, writing the bundled default first when it does not exist.
     *
     * @throws IOException if the file cannot be read or contains invalid values
     */
    public static KansokushaConfig load(Path dataDirectory) throws IOException {
        var filepath = dataDirectory.resolve(FILENAME);
        if (Files.notExists(filepath)) {
            Files.createDirectories(dataDirectory);
            try (var in = KansokushaConfig.class.getResourceAsStream("/" + FILENAME)) {
                Files.copy(Objects.requireNonNull(in, "The bundled config.yml is missing."), filepath);
            }
        }

        var raw = YamlConfigurationLoader.builder().path(filepath).build().load().get(RawConfig.class);
        if (raw == null) {
            throw invalid("the root node must be a map");
        }
        return raw.validate();
    }

    /**
     * Resource limits of the DuckDB database.
     *
     * @param memoryLimit the maximum memory DuckDB may use, such as {@code 8GB}; empty uses the DuckDB default
     * @param threads     the number of threads DuckDB may use; 0 uses the DuckDB default
     */
    public record DuckDb(Optional<String> memoryLimit, int threads) {

        public static final DuckDb DEFAULT = new DuckDb(Optional.empty(), 0);

        public DuckDb {
            Objects.requireNonNull(memoryLimit, "memoryLimit");
        }
    }

    /**
     * Recording and Discord notification of ore veins found by players. Paper only.
     *
     * @param webhookUrl     the Discord webhook URL; empty records veins without notifying Discord
     * @param ores           block types whose veins are recorded and notified; empty disables both
     * @param maxSearchCount the maximum number of blocks inspected around a broken ore
     * @param format         the message, with {@code %player_name%}, {@code %block_type%},
     *                       {@code %block_world%}, {@code %block_location%} and {@code %vein_count%}
     * @param displayNames   names shown for {@code %block_type%}; other types use their key
     */
    public record OreVein(
        Optional<URI> webhookUrl,
        Set<Key> ores,
        int maxSearchCount,
        String format,
        Map<Key, String> displayNames
    ) {

        public static final OreVein DISABLED =
            new OreVein(Optional.empty(), Set.of(), 100, "", Map.of());

        public OreVein {
            Objects.requireNonNull(webhookUrl, "webhookUrl");
            ores = Set.copyOf(ores);
            Objects.requireNonNull(format, "format");
            displayNames = Map.copyOf(displayNames);
        }

        public boolean enabled() {
            return !this.ores.isEmpty();
        }
    }

    /**
     * Retention periods resolved per event type.
     *
     * @param archivedEventTypes event types whose expired events are archived instead of deleted
     */
    public record Retention(Map<Key, Duration> durations, Duration defaultDuration, Set<Key> archivedEventTypes) {

        public Retention {
            durations = Map.copyOf(durations);
            Objects.requireNonNull(defaultDuration, "defaultDuration");
            archivedEventTypes = Set.copyOf(archivedEventTypes);
        }

        public Retention(Map<Key, Duration> durations, Duration defaultDuration) {
            this(durations, defaultDuration, Set.of());
        }

        public Duration durationOf(Key eventType) {
            return this.durations.getOrDefault(eventType, this.defaultDuration);
        }
    }

    @ConfigSerializable
    static final class RawConfig {

        String serverKey = "";
        int queueCapacity = 0;
        int batchSize = 0;
        String flushInterval = "";
        String cleanupInterval = "";
        // Configurations written before archiving existed do not have this key.
        String archiveInterval = "P1D";
        @Setting("search-time-zone")
        String searchTimeZone = "";
        RawRetention retention = new RawRetention();
        // Configurations written before these limits existed do not have this key.
        RawDuckDb duckdb = new RawDuckDb();
        // Configurations written before this notification existed do not have this key.
        @Setting("ore-vein")
        RawOreVein oreVein = new RawOreVein();

        KansokushaConfig validate() throws IOException {
            if (this.queueCapacity <= 0) {
                throw invalid("queue-capacity must be positive");
            }
            if (this.batchSize <= 0) {
                throw invalid("batch-size must be positive");
            }
            return new KansokushaConfig(
                this.serverKey == null || this.serverKey.isBlank()
                    ? Optional.empty()
                    : Optional.of(parseKey(this.serverKey, "server-key")),
                this.queueCapacity,
                this.batchSize,
                parseDuration(this.flushInterval, "flush-interval"),
                parseDuration(this.cleanupInterval, "cleanup-interval"),
                parseDuration(this.archiveInterval, "archive-interval"),
                parseZoneId(this.searchTimeZone, "search-time-zone"),
                Objects.requireNonNullElseGet(this.retention, RawRetention::new).validate(),
                Objects.requireNonNullElseGet(this.duckdb, RawDuckDb::new).validate(),
                Objects.requireNonNullElseGet(this.oreVein, RawOreVein::new).validate()
            );
        }
    }

    @ConfigSerializable
    static final class RawOreVein {

        @Setting("webhook-url")
        String webhookUrl = "";
        List<String> ores = List.of();
        @Setting("max-search-count")
        int maxSearchCount = 100;
        String format = "%player_name% found a vein of **%block_type%** x%vein_count% at %block_location% in %block_world%";
        @Setting("display-names")
        Map<String, String> displayNames = Map.of();

        OreVein validate() throws IOException {
            var path = "ore-vein";
            var url = Objects.requireNonNullElse(this.webhookUrl, "").strip();
            URI uri = null;
            if (!url.isEmpty()) {
                // The URL contains the webhook token, so it is never included in the message.
                try {
                    uri = new URI(url);
                } catch (URISyntaxException e) {
                    throw invalid(path + ".webhook-url is not a valid URL");
                }
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                    throw invalid(path + ".webhook-url must be an https:// URL");
                }
            }
            if (this.maxSearchCount <= 0) {
                throw invalid(path + ".max-search-count must be positive");
            }
            var ores = new HashSet<Key>();
            for (var ore : Objects.requireNonNullElse(this.ores, List.<String>of())) {
                ores.add(parseKey(ore, path + ".ores"));
            }
            var displayNames = new HashMap<Key, String>();
            for (var entry : Objects.requireNonNullElse(this.displayNames, Map.<String, String>of()).entrySet()) {
                displayNames.put(parseKey(entry.getKey(), path + ".display-names"), Objects.requireNonNullElse(entry.getValue(), ""));
            }
            return new OreVein(Optional.ofNullable(uri), ores, this.maxSearchCount,
                Objects.requireNonNullElse(this.format, ""), displayNames);
        }
    }

    @ConfigSerializable
    static final class RawDuckDb {

        @Setting("memory-limit")
        String memoryLimit = "";
        int threads = 0;

        DuckDb validate() throws IOException {
            var limit = this.memoryLimit == null ? "" : this.memoryLimit.strip();
            if (!limit.isEmpty() && !MEMORY_LIMIT.matcher(limit).matches()) {
                throw invalid("duckdb.memory-limit must be a size such as 8GB or 512MiB: " + this.memoryLimit);
            }
            if (this.threads < 0) {
                throw invalid("duckdb.threads must not be negative");
            }
            return new DuckDb(limit.isEmpty() ? Optional.empty() : Optional.of(limit), this.threads);
        }
    }

    @ConfigSerializable
    static final class RawRetention {

        @Setting("default")
        String defaultDuration = "";
        Map<String, RawPolicy> policies = Map.of();

        Retention validate() throws IOException {
            var durations = new HashMap<Key, Duration>();
            var archived = new HashSet<Key>();
            for (var entry : Objects.requireNonNullElse(this.policies, Map.<String, RawPolicy>of()).entrySet()) {
                var path = "retention.policies." + entry.getKey();
                var policy = Objects.requireNonNullElseGet(entry.getValue(), RawPolicy::new);
                var duration = parseDuration(policy.duration, path + ".duration");
                for (var eventType : Objects.requireNonNullElse(policy.eventTypes, List.<String>of())) {
                    var key = parseKey(eventType, path + ".event-types");
                    if (durations.put(key, duration) != null) {
                        throw invalid(eventType + " is listed in more than one retention policy");
                    }
                    if (policy.archive) {
                        archived.add(key);
                    }
                }
            }
            return new Retention(durations, parseDuration(this.defaultDuration, "retention.default"), archived);
        }
    }

    @ConfigSerializable
    static final class RawPolicy {

        String duration = "";
        boolean archive = false;
        List<String> eventTypes = List.of();
    }

    private static Duration parseDuration(String value, String path) throws IOException {
        try {
            var duration = Duration.parse(Objects.requireNonNullElse(value, ""));
            if (duration.toMillis() <= 0) {
                throw invalid(path + " must be at least 1 millisecond");
            }
            return duration;
        } catch (DateTimeParseException e) {
            throw invalid(path + " must be an ISO-8601 duration such as PT1S or P30D: " + value);
        }
    }

    private static ZoneId parseZoneId(String value, String path) throws IOException {
        try {
            return value == null || value.isBlank() ? ZoneId.systemDefault() : ZoneId.of(value);
        } catch (DateTimeException e) {
            throw invalid(path + " must be a valid IANA time zone such as UTC or Asia/Tokyo: " + value);
        }
    }

    private static Key parseKey(String value, String path) throws IOException {
        // Key.key("value") silently falls back to the "minecraft" namespace, so require it explicitly.
        if (value == null || value.indexOf(Key.DEFAULT_SEPARATOR) <= 0) {
            throw invalid(path + " must be a namespaced key such as example:value: " + value);
        }
        try {
            return Key.key(value);
        } catch (InvalidKeyException e) {
            throw invalid(path + " is not a valid namespaced key: " + value);
        }
    }

    private static IOException invalid(String message) {
        return new IOException("Invalid Kansokusha configuration: " + message);
    }
}
