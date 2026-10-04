package net.okocraft.kansokusha.paper.command;

import dev.siroshun.mcmsgdef.DefaultMessageDefiner;
import dev.siroshun.mcmsgdef.MessageKey;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.Map;

@NotNullByDefault
public final class InspectionCommandMessages {

    private static final DefaultMessageDefiner DEFINER = DefaultMessageDefiner.create();

    public static final MessageKey ENABLED = DEFINER
        .define(
            "kansokusha.command.inspect.enabled",
            "<gray>Inspection mode <green>enabled</green>. Left-click a block to view its history; right-click to inspect the position beyond the clicked face.<newline>Run <aqua>/kansokusha inspect off</aqua> to resume normal block interactions.</gray>"
        );

    public static final MessageKey DISABLED = DEFINER
        .define(
            "kansokusha.command.inspect.disabled",
            "<gray>Inspection mode <red>disabled</red>. Normal block interactions are restored.</gray>"
        );

    public static final MessageKey PLAYER_ONLY = DEFINER
        .define("kansokusha.command.inspect.player-only", "<red>This command requires a player. Run it in-game.</red>");

    public static final MessageKey HELP_INSPECT = DEFINER
        .define(
            "kansokusha.command.help.inspect",
            "<aqua>/kansokusha inspect [on/off]</aqua><dark_gray> - </dark_gray><gray>Toggle inspection mode</gray>"
        );

    public static @UnmodifiableView Map<String, String> defaultMessages() {
        return DEFINER.getCollectedMessages();
    }

    private InspectionCommandMessages() {
        throw new UnsupportedOperationException();
    }
}
