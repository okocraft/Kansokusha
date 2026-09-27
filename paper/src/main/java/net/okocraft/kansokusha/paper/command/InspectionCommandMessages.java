package net.okocraft.kansokusha.paper.command;

import dev.siroshun.mcmsgdef.DefaultMessageDefiner;
import dev.siroshun.mcmsgdef.MessageKey;

public final class InspectionCommandMessages {

    public static final DefaultMessageDefiner DEFINER = DefaultMessageDefiner.create();

    public static final MessageKey ENABLED = DEFINER
        .define("kansokusha.command.inspect.enabled", "Inspection mode enabled.");

    public static final MessageKey DISABLED = DEFINER
        .define("kansokusha.command.inspect.disabled", "Inspection mode disabled.");

    public static final MessageKey PLAYER_ONLY = DEFINER
        .define("kansokusha.command.inspect.player-only", "This command can only be used by a player.");

    private InspectionCommandMessages() {
        throw new UnsupportedOperationException();
    }
}
