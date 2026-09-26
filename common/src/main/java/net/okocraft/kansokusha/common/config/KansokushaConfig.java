package net.okocraft.kansokusha.common.config;

import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNullByDefault;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Validated contents of {@code config.yml}.
 */
@NotNullByDefault
public record KansokushaConfig(
    Optional<Key> serverKey,
    int queueCapacity,
    Duration flushInterval,
    Duration cleanupInterval,
    Retention retention
) {

    private static final String FILENAME = "config.yml";

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
     * Retention periods resolved per event type.
     */
    public record Retention(Map<Key, Duration> durations, Duration defaultDuration) {

        public Retention {
            durations = Map.copyOf(durations);
            Objects.requireNonNull(defaultDuration, "defaultDuration");
        }

        public Duration durationOf(Key eventType) {
            return this.durations.getOrDefault(eventType, this.defaultDuration);
        }
    }

    @ConfigSerializable
    static final class RawConfig {

        String serverKey = "";
        int queueCapacity = 0;
        String flushInterval = "";
        String cleanupInterval = "";
        RawRetention retention = new RawRetention();

        KansokushaConfig validate() throws IOException {
            if (this.queueCapacity <= 0) {
                throw invalid("queue-capacity must be positive");
            }
            return new KansokushaConfig(
                this.serverKey == null || this.serverKey.isBlank()
                    ? Optional.empty()
                    : Optional.of(parseKey(this.serverKey, "server-key")),
                this.queueCapacity,
                parseDuration(this.flushInterval, "flush-interval"),
                parseDuration(this.cleanupInterval, "cleanup-interval"),
                Objects.requireNonNullElseGet(this.retention, RawRetention::new).validate()
            );
        }
    }

    @ConfigSerializable
    static final class RawRetention {

        @Setting("default")
        String defaultDuration = "";
        Map<String, RawPolicy> policies = Map.of();

        Retention validate() throws IOException {
            var durations = new HashMap<Key, Duration>();
            for (var entry : Objects.requireNonNullElse(this.policies, Map.<String, RawPolicy>of()).entrySet()) {
                var path = "retention.policies." + entry.getKey();
                var policy = Objects.requireNonNullElseGet(entry.getValue(), RawPolicy::new);
                var duration = parseDuration(policy.duration, path + ".duration");
                for (var eventType : Objects.requireNonNullElse(policy.eventTypes, List.<String>of())) {
                    if (durations.put(parseKey(eventType, path + ".event-types"), duration) != null) {
                        throw invalid(eventType + " is listed in more than one retention policy");
                    }
                }
            }
            return new Retention(durations, parseDuration(this.defaultDuration, "retention.default"));
        }
    }

    @ConfigSerializable
    static final class RawPolicy {

        String duration = "";
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
