package net.okocraft.kansokusha.neoforge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.okocraft.kansokusha.common.command.EventLocation;

import java.util.Set;

final class NativeTeleport {
    static Component format(EventLocation location, boolean showWorld, boolean teleport) {
        Component result = Component.empty();
        if (showWorld) {
            result = location.world().map(key -> (Component) Component.text(key.value())
                .hoverEvent(HoverEvent.showText(Component.text(key.asString()))))
                .orElseGet(() -> location.server().map(key -> (Component) Component.text(key.asString()))
                    .orElse(Component.empty()));
        }
        if (location.position().isPresent()) {
            var point = location.position().get();
            Component coordinate = Component.text("(" + point.x() + "," + point.y() + "," + point.z() + ")");
            if (teleport && location.world().isPresent()) {
                coordinate = coordinate.clickEvent(ClickEvent.runCommand("/kansokusha teleport "
                    + location.world().get().asString() + " " + point.x() + " " + point.y() + " " + point.z()))
                    .hoverEvent(HoverEvent.showText(Component.text("Teleport here")));
            }
            if (!result.equals(Component.empty())) result = result.append(Component.space());
            result = result.append(coordinate);
        }
        return result;
    }

    static int execute(CommandSourceStack source, String raw) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        var parts = raw.strip().split("\\s+");
        if (parts.length != 4) return fail(source, "Usage: /kansokusha teleport <world> <x> <y> <z>");
        try {
            var key = ResourceKey.create(Registries.DIMENSION, Identifier.parse(parts[0]));
            var level = source.getServer().getLevel(key);
            if (level == null) return fail(source, "World is unavailable");
            var x = Integer.parseInt(parts[1]);
            var y = Integer.parseInt(parts[2]);
            var z = Integer.parseInt(parts[3]);
            if (!NativePermissions.has(source, "kansokusha.command.teleport")) return 0;
            player.teleportTo(level, x + 0.5, y, z + 0.5, Set.of(), player.getYRot(), player.getXRot(), true);
            return 1;
        } catch (IllegalArgumentException failure) {
            return fail(source, "Invalid world or coordinates");
        }
    }

    private static int fail(CommandSourceStack source, String message) {
        NativeCommands.send(source).accept(net.kyori.adventure.text.Component.text(message));
        return 0;
    }

    private NativeTeleport() { }
}
