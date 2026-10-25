package net.okocraft.kansokusha.paper.command;

import dev.siroshun.mcmsgdef.DefaultMessageDefiner;
import dev.siroshun.mcmsgdef.MessageKey;
import java.util.Map;

public final class TeleportMessages {
    private static final DefaultMessageDefiner DEFINER = DefaultMessageDefiner.create();
    public static final MessageKey PLAYER_ONLY = DEFINER.define("kansokusha.command.teleport.player-only", "<red>Only players can teleport.</red>");
    public static final MessageKey WORLD_UNAVAILABLE = DEFINER.define("kansokusha.command.teleport.world-unavailable", "<red>This world is not loaded.</red>");
    public static final MessageKey FAILED = DEFINER.define("kansokusha.command.teleport.failed", "<red>Teleportation failed.</red>");
    public static final MessageKey HOVER = DEFINER.define("kansokusha.command.teleport.hover", "Click to teleport");
    public static Map<String, String> defaultMessages() { return DEFINER.getCollectedMessages(); }
    private TeleportMessages() { }
}
