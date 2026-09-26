package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.RemoteServerCommandEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.util.Objects;

/**
 * Records non-player command input as observed at Paper's server command boundary.
 */
@ApiStatus.Internal
@NotNullByDefault
public final class PaperServerCommandListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "paper_server_command");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;

    private PaperServerCommandListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperServerCommandListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperServerCommandListener register(KansokushaApi api, Key serverKey, Clock clock) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperServerCommandListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void record(ServerCommandEvent event) {
        Objects.requireNonNull(event, "event");
        if (event instanceof RemoteServerCommandEvent) {
            return;
        }
        this.recordCommand(event, false);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void recordRemote(RemoteServerCommandEvent event) {
        Objects.requireNonNull(event, "event");
        this.recordCommand(event, true);
    }

    private void recordCommand(ServerCommandEvent event, boolean remoteEvent) {
        var occurredAt = this.clock.instant();
        var originalCommand = event.getCommand();
        var source = snapshotSource(event, remoteEvent);

        this.api.submit(
            new EventSubmission(
                EVENT_TYPE,
                PayloadGeneration.FIRST,
                occurredAt,
                this.serverKey,
                source.worldKey(),
                source.position(),
                PaperAdministrativeSource.actor(event.getSender()),
                null,
                PaperCommunicationPayloadCodec.encodeServerCommand(
                    source.kind(),
                    source.name(),
                    originalCommand
                )
            )
        );
    }

    private static SourceSnapshot snapshotSource(ServerCommandEvent event, boolean remoteEvent) {
        var sender = event.getSender();

        if (sender instanceof BlockCommandSender blockSender) {
            var block = blockSender.getBlock();
            var world = block.getWorld();
            return new SourceSnapshot(
                "command_block",
                sender.getName(),
                PaperKansokusha.key(world.getKey()),
                PaperBuiltInSupport.position(block)
            );
        }

        var kind = remoteEvent || sender instanceof RemoteConsoleCommandSender
            ? "rcon"
            : sender instanceof ConsoleCommandSender ? "console" : "other";
        return new SourceSnapshot(kind, sender.getName(), null, null);
    }

    private record SourceSnapshot(
        String kind,
        String name,
        @Nullable Key worldKey,
        @Nullable BlockPosition position
    ) {
    }
}
