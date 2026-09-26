package net.okocraft.kansokusha.paper.builtin;

import io.papermc.paper.event.world.WorldDifficultyChangeEvent;
import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.Difficulty;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Records established Paper world difficulty state changes. */
@ApiStatus.Internal
@NotNullByDefault
public final class PaperWorldDifficultyChangeListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "world_difficulty_change");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final PaperInFlightMap<WorldDifficultyChangeEvent, Snapshot> inFlight =
        new PaperInFlightMap<>();

    private PaperWorldDifficultyChangeListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperWorldDifficultyChangeListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperWorldDifficultyChangeListener register(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperWorldDifficultyChangeListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(WorldDifficultyChangeEvent event) {
        Objects.requireNonNull(event, "event");

        var world = event.getWorld();
        this.inFlight.put(event, new Snapshot(
            this.clock.instant(),
            PaperKansokusha.key(world.getKey()),
            world.getDifficulty(),
            world.isHardcore(),
            PaperAdministrativeSource.snapshot(event.getCommandSource())
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(WorldDifficultyChangeEvent event) {
        Objects.requireNonNull(event, "event");

        var snapshot = this.inFlight.remove(event);
        if (snapshot == null) {
            return;
        }

        var after = snapshot.hardcore() ? Difficulty.HARD : event.getDifficulty();
        if (snapshot.before() == after) {
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
            PaperAdministrativePayloadCodec.encodeDifficultyChange(
                PaperAdministrativePayloadCodec.enumName(snapshot.before()),
                PaperAdministrativePayloadCodec.enumName(after),
                snapshot.source()
            )
        ));
    }

    int inFlightCount() {
        return this.inFlight.size();
    }

    private record Snapshot(
        Instant occurredAt,
        Key worldKey,
        Difficulty before,
        boolean hardcore,
        @Nullable PaperAdministrativeSource.Snapshot source
    ) {
    }
}
