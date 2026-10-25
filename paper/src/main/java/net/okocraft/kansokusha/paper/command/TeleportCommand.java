package net.okocraft.kansokusha.paper.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

final class TeleportCommand {
    static final String PERMISSION = "kansokusha.command.teleport";

    static LiteralArgumentBuilder<CommandSourceStack> create(Plugin plugin) {
        return Commands.literal("teleport").requires(source -> source.getSender().hasPermission(PERMISSION))
            .then(Commands.argument("world", StringArgumentType.word())
                .then(Commands.argument("x", IntegerArgumentType.integer())
                    .then(Commands.argument("y", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer()).executes(context -> {
                            var sender = context.getSource().getSender();
                            if (!(sender instanceof Player player)) {
                                sender.sendMessage(TeleportMessages.PLAYER_ONLY.asComponent());
                                return 0;
                            }
                            var worldKey = NamespacedKey.fromString(StringArgumentType.getString(context, "world"));
                            var x = IntegerArgumentType.getInteger(context, "x");
                            var y = IntegerArgumentType.getInteger(context, "y");
                            var z = IntegerArgumentType.getInteger(context, "z");
                            return player.getScheduler().execute(plugin, () -> {
                                if (!player.hasPermission(PERMISSION)) return;
                                var world = worldKey == null ? null : plugin.getServer().getWorld(worldKey);
                                if (world == null) {
                                    player.sendMessage(TeleportMessages.WORLD_UNAVAILABLE.asComponent());
                                    return;
                                }
                                var current = player.getLocation();
                                player.teleportAsync(new Location(world, x + 0.5, y, z + 0.5, current.getYaw(), current.getPitch()))
                                    .whenComplete((success, failure) -> {
                                        if (failure != null || !Boolean.TRUE.equals(success)) player.sendMessage(TeleportMessages.FAILED.asComponent());
                                    });
                            }, null, 1) ? 1 : 0;
                        })))));
    }

    private TeleportCommand() { }
}
