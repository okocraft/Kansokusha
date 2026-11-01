package net.okocraft.kansokusha.velocity.command;

import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import net.okocraft.kansokusha.common.command.HealthCommandSupport;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import org.jetbrains.annotations.NotNullByDefault;

/**
 * {@code /kansokusha admin}: operations on the database itself rather than on the recorded events.
 */
@NotNullByDefault
final class AdminCommand {

    static LiteralCommandNode<CommandSource> createAdminCommand(EventSearchBackend backend) {
        return BrigadierCommand.literalArgumentBuilder("admin")
            .requires(source -> source.hasPermission(HealthCommandSupport.PERMISSION))
            .then(HealthCommand.createHealthCommand(backend))
            .build();
    }

    private AdminCommand() {
        throw new UnsupportedOperationException();
    }
}
