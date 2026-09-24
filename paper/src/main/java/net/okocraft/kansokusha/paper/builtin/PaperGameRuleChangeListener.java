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
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Records accepted Paper world gamerule state changes. */
@ApiStatus.Internal
@NotNullByDefault
public final class PaperGameRuleChangeListener implements PaperInFlightListener {

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
        this.inFlight.put(event, new Snapshot(
            this.clock.instant(),
            PaperKansokusha.key(world.getKey()),
            PaperKansokusha.key(gameRule.getKey()).asString(),
            currentValue(world.getGameRuleValue(gameRule)),
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

        var after = event.getValue();
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
            null,
            PaperAdministrativePayloadCodec.encodeGameRuleChange(
                snapshot.gameRule(),
                snapshot.before(),
                after,
                snapshot.source()
            )
        ));
    }

    @Override
    public void clearInFlightState() {
        this.inFlight.clear();
    }

    int inFlightCount() {
        return this.inFlight.size();
    }

    private static String currentValue(Object value) {
        return String.valueOf(Objects.requireNonNull(value, "gameRuleValue"));
    }

    private record Snapshot(
        Instant occurredAt,
        Key worldKey,
        String gameRule,
        String before,
        PaperAdministrativeSource.Snapshot source
    ) {
    }
}
