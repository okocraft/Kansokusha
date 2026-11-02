package net.okocraft.kansokusha.neoforge;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.permissions.Permissions;
import net.neoforged.fml.ModList;

final class NativePermissions {
    static boolean has(CommandSourceStack source, String node) {
        if (!(source.getEntity() instanceof ServerPlayer player))
            return source.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
        if (ModList.get().isLoaded("luckperms")) {
            var value = LuckPermsAccess.permission(player, node);
            if (value != null) return value;
        }
        if (node.equals("kansokusha.command.event.debug")) return false;
        return player.level().getServer().getPlayerList().isOp(new NameAndId(player.getGameProfile()));
    }

    private static final class LuckPermsAccess {
        static Boolean permission(ServerPlayer player, String node) {
            var api = net.luckperms.api.LuckPermsProvider.get();
            var user = api.getUserManager().getUser(player.getUUID());
            if (user == null) return null;
            var options = api.getContextManager().getQueryOptions(user).orElse(api.getContextManager().getStaticQueryOptions());
            var value = user.getCachedData().getPermissionData(options).checkPermission(node);
            return value == net.luckperms.api.util.Tristate.UNDEFINED ? null : value.asBoolean();
        }
    }

    private NativePermissions() { }
}
