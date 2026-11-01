package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.actor.EventActor;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records non-player command input as observed at Paper's server command boundary.
 *
 * <p>Repeating command blocks run their command every tick, which would otherwise dominate the
 * database. Runs of the same command by the same command block are summed per
 * {@link #COMMAND_BLOCK_WINDOW} into one event that carries the number of runs and is stamped with
 * the start of its window. Console and RCON input is recorded one by one.</p>
 */
@ApiStatus.Internal
@NotNullByDefault
public final class PaperServerCommandListener implements Listener, PaperBufferedListener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "paper_server_command");
    static final Duration COMMAND_BLOCK_WINDOW = Duration.ofMinutes(1);

    // Bounds the memory used by the aggregation; commands beyond it are submitted one by one.
    static final int MAX_PENDING = 100_000;

    private final EventSearchBackend searchBackend;
    private final Key serverKey;
    private final Clock clock;
    private final ConcurrentHashMap<CommandBlockKey, Pending> pending = new ConcurrentHashMap<>();

    private PaperServerCommandListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.searchBackend = EventSearchBackend.require(api);
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
        var actor = PaperAdministrativeSource.actor(event.getSender());

        if (!source.kind().equals("command_block") || source.worldKey() == null || source.position() == null) {
            this.submit(occurredAt, source, actor, originalCommand, null);
            return;
        }

        var key = new CommandBlockKey(source.worldKey(), source.position(), source.name(), originalCommand);
        while (true) {
            var existing = this.pending.get(key);
            if (existing != null) {
                if (!existing.isDue(occurredAt) && existing.add()) {
                    return;
                }
                this.complete(key, existing);
                continue;
            }

            var created = new Pending(occurredAt, source, actor, originalCommand);
            if (this.pending.size() >= MAX_PENDING) {
                this.submit(created, 1);
                return;
            }
            if (this.pending.putIfAbsent(key, created) == null) {
                return;
            }
        }
    }

    @Override
    public void flushDue() {
        var now = this.clock.instant();
        this.pending.forEach((key, entry) -> {
            if (entry.isDue(now)) {
                this.complete(key, entry);
            }
        });
    }

    @Override
    public void flushAll() {
        this.pending.forEach(this::complete);
    }

    private void complete(CommandBlockKey key, Pending entry) {
        if (entry.close()) {
            this.submit(entry, entry.runs);
        }
        this.pending.remove(key, entry);
    }

    private void submit(Pending entry, int runs) {
        this.submit(entry.start, entry.source, entry.actor, entry.command, runs);
    }

    private void submit(
        Instant occurredAt,
        SourceSnapshot source,
        @Nullable EventActor actor,
        String command,
        @Nullable Integer runs
    ) {
        this.searchBackend.submitSearchable(
            new EventSubmission(
                EVENT_TYPE,
                PayloadGeneration.FIRST,
                occurredAt,
                this.serverKey,
                source.worldKey(),
                source.position(),
                actor,
                null,
                PaperCommunicationPayloadCodec.encodeServerCommand(source.kind(), source.name(), command, runs)
            ),
            command
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

    private record CommandBlockKey(Key worldKey, BlockPosition position, String sourceName, String command) {
    }

    private static final class Pending {

        private final Instant start;
        private final SourceSnapshot source;
        private final @Nullable EventActor actor;
        private final String command;
        private int runs = 1;
        private boolean closed;

        private Pending(Instant start, SourceSnapshot source, @Nullable EventActor actor, String command) {
            this.start = start;
            this.source = source;
            this.actor = actor;
            this.command = command;
        }

        private boolean isDue(Instant now) {
            return !now.isBefore(this.start.plus(COMMAND_BLOCK_WINDOW));
        }

        private synchronized boolean add() {
            if (this.closed) {
                return false;
            }
            this.runs++;
            return true;
        }

        /** Returns whether this call closed the entry; only the caller that did may submit it. */
        private synchronized boolean close() {
            if (this.closed) {
                return false;
            }
            this.closed = true;
            return true;
        }
    }

    private record SourceSnapshot(
        String kind,
        String name,
        @Nullable Key worldKey,
        @Nullable BlockPosition position
    ) {
    }
}
