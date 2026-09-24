package net.okocraft.kansokusha.paper.builtin;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

@NotNullByDefault
final class PaperAdministrativeSource {

    private PaperAdministrativeSource() {
    }

    static @Nullable Snapshot snapshot(@Nullable CommandSender sender) {
        return sender == null ? null : new Snapshot(snapshotSender(sender), null);
    }

    static @Nullable Snapshot snapshot(@Nullable CommandSourceStack source) {
        if (source == null) {
            return null;
        }

        var executor = source.getExecutor();
        return new Snapshot(
            snapshotSender(source.getSender()),
            executor == null ? null : snapshotExecutor(executor)
        );
    }

    private static SenderSnapshot snapshotSender(CommandSender sender) {
        Objects.requireNonNull(sender, "sender");

        if (sender instanceof Player player) {
            return new SenderSnapshot(
                "player",
                sender.getName(),
                player.getUniqueId().toString(),
                player.getType().key().asString()
            );
        }
        if (sender instanceof BlockCommandSender) {
            return new SenderSnapshot("command_block", sender.getName(), null, null);
        }
        if (sender instanceof RemoteConsoleCommandSender) {
            return new SenderSnapshot("rcon", sender.getName(), null, null);
        }
        if (sender instanceof ConsoleCommandSender) {
            return new SenderSnapshot("console", sender.getName(), null, null);
        }
        if (sender instanceof Entity entity) {
            return new SenderSnapshot(
                "entity",
                sender.getName(),
                entity.getUniqueId().toString(),
                entity.getType().key().asString()
            );
        }
        return new SenderSnapshot("other", sender.getName(), null, null);
    }

    private static ExecutorSnapshot snapshotExecutor(Entity executor) {
        return new ExecutorSnapshot(
            executor instanceof Player ? "player" : "entity",
            executor.getUniqueId().toString(),
            executor.getType().key().asString()
        );
    }

    record Snapshot(SenderSnapshot sender, @Nullable ExecutorSnapshot executor) {
    }

    record SenderSnapshot(
        String kind,
        String name,
        @Nullable String uniqueId,
        @Nullable String entityType
    ) {
    }

    record ExecutorSnapshot(String kind, String uniqueId, String entityType) {
    }
}
