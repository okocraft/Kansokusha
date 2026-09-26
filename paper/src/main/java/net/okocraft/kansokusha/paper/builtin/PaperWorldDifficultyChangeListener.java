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

import java.time.Clock;
import java.util.Objects;

/** Records established Paper world difficulty state changes. */
@ApiStatus.Internal
@NotNullByDefault
public final class PaperWorldDifficultyChangeListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "world_difficulty_change");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;

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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void record(WorldDifficultyChangeEvent event) {
        Objects.requireNonNull(event, "event");

        var world = event.getWorld();
        var before = world.getDifficulty();
        var after = world.isHardcore() ? Difficulty.HARD : event.getDifficulty();
        if (before == after) {
            return;
        }

        var commandSource = event.getCommandSource();
        var source = PaperAdministrativeSource.snapshot(commandSource);
        this.api.submit(new EventSubmission(
            EVENT_TYPE,
            PayloadGeneration.FIRST,
            this.clock.instant(),
            this.serverKey,
            PaperKansokusha.key(world.getKey()),
            null,
            PaperAdministrativeSource.actor(commandSource),
            null,
            PaperAdministrativePayloadCodec.encodeDifficultyChange(
                PaperAdministrativePayloadCodec.enumName(before),
                PaperAdministrativePayloadCodec.enumName(after),
                source
            )
        ));
    }
}
