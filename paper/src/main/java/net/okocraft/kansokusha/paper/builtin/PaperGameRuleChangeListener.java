package net.okocraft.kansokusha.paper.builtin;

import io.papermc.paper.event.world.WorldGameRuleChangeEvent;
import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.GameRule;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Records accepted Paper world gamerule state changes. */
@ApiStatus.Internal
@NotNullByDefault
public final class PaperGameRuleChangeListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "gamerule_change");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final PaperInFlightMap<WorldGameRuleChangeEvent, Snapshot> inFlight =
        new PaperInFlightMap<>();

    private PaperGameRuleChangeListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperGameRuleChangeListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperGameRuleChangeListener register(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperGameRuleChangeListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(WorldGameRuleChangeEvent event) {
        Objects.requireNonNull(event, "event");

        var world = event.getWorld();
        var gameRule = event.getGameRule();
        var valueType = gameRule.getType();
        this.inFlight.put(event, new Snapshot(
            this.clock.instant(),
            PaperKansokusha.key(world.getKey()),
            PaperKansokusha.key(gameRule.getKey()).asString(),
            valueType,
            currentValue(valueType, world.getGameRuleValue(gameRule)),
            PaperAdministrativeSource.snapshot(event.getCommandSender())
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(WorldGameRuleChangeEvent event) {
        Objects.requireNonNull(event, "event");

        var snapshot = this.inFlight.remove(event);
        if (snapshot == null || event.isCancelled()) {
            return;
        }

        var after = canonicalValue(snapshot.valueType(), event.getValue());
        if (snapshot.before().equals(after)) {
            return;
        }

        this.api.submit(new EventSubmission(
            EVENT_TYPE,
            PayloadGeneration.FIRST,
            snapshot.occurredAt(),
            this.serverKey,
            snapshot.worldKey(),
            null,
            PaperAdministrativeSource.subject(snapshot.source()),
            PaperAdministrativePayloadCodec.encodeGameRuleChange(
                snapshot.gameRule(),
                snapshot.before(),
                after,
                snapshot.source()
            )
        ));
    }

    int inFlightCount() {
        return this.inFlight.size();
    }

    private static String currentValue(Class<?> valueType, Object value) {
        Objects.requireNonNull(valueType, "valueType");
        Objects.requireNonNull(value, "gameRuleValue");
        if (!valueType.isInstance(value)) {
            throw new IllegalArgumentException(
                "Game rule value type mismatch: expected "
                    + valueType.getName()
                    + ", got "
                    + value.getClass().getName()
            );
        }
        return value.toString();
    }

    private static String canonicalValue(Class<?> valueType, String value) {
        Objects.requireNonNull(valueType, "valueType");
        Objects.requireNonNull(value, "value");
        if (valueType == Boolean.class) {
            return Boolean.toString(Boolean.parseBoolean(value));
        }
        if (valueType == Integer.class) {
            return Integer.toString(Integer.parseInt(value));
        }
        throw new IllegalArgumentException("Unsupported game rule value type: " + valueType.getName());
    }

    private record Snapshot(
        Instant occurredAt,
        Key worldKey,
        String gameRule,
        Class<?> valueType,
        String before,
        @Nullable PaperAdministrativeSource.Snapshot source
    ) {
    }
}
