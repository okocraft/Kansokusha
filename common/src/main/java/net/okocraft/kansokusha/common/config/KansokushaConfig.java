package net.okocraft.kansokusha.common.config;

import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNullByDefault;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@ConfigSerializable
@NotNullByDefault
public class KansokushaConfig {

    private static final String FILENAME = "config.yml";

    @Comment("More output to the console.")
    private boolean debug = false;

    @Comment("Local server identity used by single-server integrations such as Paper/Folia.")
    private String serverKey = "";

    @Comment("Bounded asynchronous event ingestion and batch writing.")
    private Ingestion ingestion = new Ingestion();

    @Comment("Event retention policies and event-type mappings.")
    private Retention retention = new Retention();

    private transient Optional<Key> localServerKey;
    private transient IngestionSettings ingestionSettings;
    private transient RetentionSettings retentionSettings;
    private transient RetentionCleanupSettings retentionCleanupSettings;

    public boolean debug() {
        return this.debug;
    }

    public Optional<Key> localServerKey() {
        var key = this.localServerKey;
        if (key == null) {
            throw new IllegalStateException("Server identity configuration has not been validated.");
        }
        return key;
    }

    public IngestionSettings ingestionSettings() {
        var settings = this.ingestionSettings;
        if (settings == null) {
            throw new IllegalStateException("Ingestion configuration has not been validated.");
        }
        return settings;
    }

    public RetentionSettings retentionSettings() {
        var settings = this.retentionSettings;
        if (settings == null) {
            throw new IllegalStateException("Retention configuration has not been validated.");
        }
        return settings;
    }

    public RetentionCleanupSettings retentionCleanupSettings() {
        var settings = this.retentionCleanupSettings;
        if (settings == null) {
            throw new IllegalStateException("Retention cleanup configuration has not been validated.");
        }
        return settings;
    }

    private void validate() throws IOException {
        this.localServerKey = this.serverKey == null || this.serverKey.isBlank()
            ? Optional.empty()
            : Optional.of(Retention.parseKey(this.serverKey, "server-key"));

        var validatedRetention = Objects.requireNonNull(this.retention, "retention").validate();
        this.retentionSettings = validatedRetention.retentionSettings();
        this.retentionCleanupSettings = validatedRetention.cleanupSettings();
        this.ingestionSettings = Objects.requireNonNull(this.ingestion, "ingestion").validate();
    }

    @ConfigSerializable
    public static final class Ingestion {

        @Comment("Maximum number of accepted events waiting for the asynchronous writer.")
        private int queueCapacity = 0;

        @Comment("Maximum number of events persisted by one writer batch.")
        private int maxBatchSize = 0;

        @Comment("Maximum time the writer waits to fill a partial batch.")
        private String maxBatchDelay = "";

        private IngestionSettings validate() throws IOException {
            if (this.queueCapacity <= 0) {
                throw invalid("ingestion.queue-capacity must be positive");
            }
            if (this.maxBatchSize <= 0) {
                throw invalid("ingestion.max-batch-size must be positive");
            }

            var delay = Retention.parseDuration(this.maxBatchDelay, "ingestion.max-batch-delay");
            try {
                delay.toNanos();
            } catch (ArithmeticException e) {
                throw invalid("ingestion.max-batch-delay exceeds the supported nanosecond range", e);
            }

            return new IngestionSettings(this.queueCapacity, this.maxBatchSize, delay);
        }
    }

    @ConfigSerializable
    public static final class Retention {

        @Comment("Retention policy definitions. Keys are namespace-qualified Adventure keys.")
        private List<Policy> policies = List.of();

        @Comment("Event-type to retention-policy mappings; optional qualifier refines one event type.")
        private List<EventTypeMapping> eventTypeMappings = List.of();

        @Comment("Fallback retention policy key for event types without an exact mapping.")
        private String fallbackPolicy = "";

        @Comment("Fixed delay between automatic retention cleanup passes.")
        private String cleanupInterval = "";

        @Comment("Maximum number of expired event rows deleted by one cleanup pass.")
        private int maxRowsPerPass = 0;

        private ValidatedRetention validate() throws IOException {
            var policiesByKey = new LinkedHashMap<Key, Duration>();
            var configuredPolicies = requireList(this.policies, "retention.policies");

            for (int index = 0; index < configuredPolicies.size(); index++) {
                var policy = requireEntry(configuredPolicies.get(index), "retention.policies[" + index + "]");
                var key = parseKey(policy.key, "retention.policies[" + index + "].key");
                var duration = parseDuration(policy.duration, "retention.policies[" + index + "].duration");

                if (policiesByKey.putIfAbsent(key, duration) != null) {
                    throw invalid("duplicate retention policy key: " + key.asString());
                }
            }

            if (policiesByKey.isEmpty()) {
                throw invalid("retention.policies must define at least one policy");
            }

            var mappingsByEventType = new LinkedHashMap<Key, Key>();
            var qualifiedMappings = new LinkedHashMap<QualifiedEventType, Key>();
            var configuredMappings = requireList(
                this.eventTypeMappings,
                "retention.event-type-mappings"
            );

            for (int index = 0; index < configuredMappings.size(); index++) {
                var path = "retention.event-type-mappings[" + index + "]";
                var mapping = requireEntry(configuredMappings.get(index), path);
                var eventType = parseKey(mapping.eventType, path + ".event-type");
                var policyKey = parseKey(mapping.policy, path + ".policy");

                if (mapping.qualifier == null || mapping.qualifier.isBlank()) {
                    if (mappingsByEventType.putIfAbsent(eventType, policyKey) != null) {
                        throw invalid("duplicate event type mapping: " + eventType.asString());
                    }
                } else {
                    var qualifier = parseKey(mapping.qualifier, path + ".qualifier");
                    var qualifiedEventType = new QualifiedEventType(eventType, qualifier);
                    if (qualifiedMappings.putIfAbsent(qualifiedEventType, policyKey) != null) {
                        throw invalid(
                            "duplicate qualified event type mapping: "
                                + eventType.asString()
                                + " / "
                                + qualifier.asString()
                        );
                    }
                }
                requireKnownPolicy(policiesByKey, policyKey, "event type " + eventType.asString());
            }

            var fallback = parseKey(this.fallbackPolicy, "retention.fallback-policy");
            requireKnownPolicy(policiesByKey, fallback, "fallback policy");

            var interval = parseDuration(this.cleanupInterval, "retention.cleanup-interval");
            if (this.maxRowsPerPass <= 0) {
                throw invalid("retention.max-rows-per-pass must be positive");
            }

            return new ValidatedRetention(
                new RetentionSettings(
                    policiesByKey,
                    mappingsByEventType,
                    qualifiedMappings,
                    fallback
                ),
                new RetentionCleanupSettings(interval, this.maxRowsPerPass)
            );
        }

        private static Duration parseDuration(String value, String path) throws IOException {
            if (value == null || value.isBlank()) {
                throw invalid(path + " must be a non-blank ISO-8601 duration");
            }

            final Duration duration;
            try {
                duration = Duration.parse(value);
            } catch (DateTimeParseException e) {
                throw invalid(path + " is not a valid ISO-8601 duration: " + value, e);
            }

            if (duration.isZero() || duration.isNegative()) {
                throw invalid(path + " must be positive");
            }
            if (duration.getNano() % 1_000_000 != 0) {
                throw invalid(path + " must resolve to whole milliseconds");
            }

            try {
                duration.toMillis();
            } catch (ArithmeticException e) {
                throw invalid(path + " exceeds the supported millisecond range", e);
            }

            return duration;
        }

        private static Key parseKey(String value, String path) throws IOException {
            if (value == null || value.isBlank()) {
                throw invalid(path + " must be a non-blank namespace-qualified key");
            }
            if (value.indexOf(Key.DEFAULT_SEPARATOR) <= 0) {
                throw invalid(path + " must explicitly include a namespace: " + value);
            }

            try {
                return Key.key(value);
            } catch (InvalidKeyException e) {
                throw invalid(path + " is not a valid namespace-qualified key: " + value, e);
            }
        }

        private static void requireKnownPolicy(
            Map<Key, Duration> policies,
            Key policyKey,
            String reference
        ) throws IOException {
            if (!policies.containsKey(policyKey)) {
                throw invalid(reference + " references unknown retention policy " + policyKey.asString());
            }
        }

        private static <T> List<T> requireList(List<T> list, String path) throws IOException {
            if (list == null) {
                throw invalid(path + " must be a list");
            }
            return list;
        }

        private static <T> T requireEntry(T entry, String path) throws IOException {
            if (entry == null) {
                throw invalid(path + " must not be null");
            }
            return entry;
        }
    }


    private record ValidatedRetention(
        RetentionSettings retentionSettings,
        RetentionCleanupSettings cleanupSettings
    ) {
    }

    @ConfigSerializable
    public static final class Policy {

        private String key = "";
        private String duration = "";
    }

    @ConfigSerializable
    public static final class EventTypeMapping {

        private String eventType = "";
        private String qualifier = "";
        private String policy = "";
    }

    public record IngestionSettings(
        int queueCapacity,
        int maxBatchSize,
        Duration maxBatchDelay
    ) {
    }

    public record RetentionCleanupSettings(
        Duration interval,
        int maxRowsPerPass
    ) {
    }

    public record QualifiedEventType(Key eventType, Key qualifier) {

        public QualifiedEventType {
            Objects.requireNonNull(eventType, "eventType");
            Objects.requireNonNull(qualifier, "qualifier");
        }
    }

    public record RetentionSettings(
        Map<Key, Duration> policies,
        Map<Key, Key> eventTypeMappings,
        Map<QualifiedEventType, Key> qualifiedEventTypeMappings,
        Key fallbackPolicy
    ) {

        public RetentionSettings(
            Map<Key, Duration> policies,
            Map<Key, Key> eventTypeMappings,
            Key fallbackPolicy
        ) {
            this(policies, eventTypeMappings, Map.of(), fallbackPolicy);
        }

        public RetentionSettings {
            policies = Map.copyOf(Objects.requireNonNull(policies, "policies"));
            eventTypeMappings = Map.copyOf(
                Objects.requireNonNull(eventTypeMappings, "eventTypeMappings")
            );
            qualifiedEventTypeMappings = Map.copyOf(
                Objects.requireNonNull(
                    qualifiedEventTypeMappings,
                    "qualifiedEventTypeMappings"
                )
            );
            Objects.requireNonNull(fallbackPolicy, "fallbackPolicy");
        }
    }

    private static IOException invalid(String message) {
        return new IOException("Invalid Kansokusha configuration: " + message);
    }

    private static IOException invalid(String message, Exception cause) {
        return new IOException("Invalid Kansokusha configuration: " + message, cause);
    }

    public static final class Holder {

        private final ConfigLoader<KansokushaConfig> loader;
        private final AtomicReference<KansokushaConfig> ref;

        public Holder(Path dataDirectory) {
            this.loader = new ConfigLoader<>(
                Objects.requireNonNull(dataDirectory).resolve(FILENAME),
                KansokushaConfig.class,
                KansokushaConfig::new
            );
            this.ref = new AtomicReference<>(new KansokushaConfig());
        }

        public KansokushaConfig get() {
            return this.ref.get();
        }

        public void reload() throws IOException {
            this.reload(config -> {
            });
        }

        public synchronized void reload(Consumer<KansokushaConfig> beforeActivate)
            throws IOException {
            Objects.requireNonNull(beforeActivate, "beforeActivate");
            var loaded = this.loader.load();
            loaded.validate();
            beforeActivate.accept(loaded);
            this.ref.set(loaded);
        }
    }
}
