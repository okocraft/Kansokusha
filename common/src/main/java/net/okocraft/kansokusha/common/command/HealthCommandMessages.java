package net.okocraft.kansokusha.common.command;

import dev.siroshun.mcmsgdef.DefaultMessageDefiner;
import dev.siroshun.mcmsgdef.MessageKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.translation.Argument;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.Map;

@NotNullByDefault
public final class HealthCommandMessages {

    private static final DefaultMessageDefiner DEFINER = DefaultMessageDefiner.create();

    public static final MessageKey HEADER = DEFINER
        .define("kansokusha.command.health.header", "<gold>Kansokusha database health</gold>");

    public static final MessageKey FAILED = DEFINER
        .define(
            "kansokusha.command.health.failed",
            "<red>Failed to read the database health. Check the server log.</red>"
        );

    public static final MessageKey.Arg2<Component, Component> LINE = DEFINER
        .define(
            "kansokusha.command.health.line",
            "<gray><label><dark_gray>: </dark_gray><aqua><value></aqua></gray>"
        )
        .with(label -> Argument.component("label", label), value -> Argument.component("value", value));

    public static final MessageKey EVENTS = DEFINER
        .define("kansokusha.command.health.label.events", "Stored events");

    public static final MessageKey PLAYER_NAMES = DEFINER
        .define("kansokusha.command.health.label.player-names", "Stored player names");

    public static final MessageKey OLDEST_EVENT = DEFINER
        .define("kansokusha.command.health.label.oldest-event", "Oldest event");

    public static final MessageKey NEWEST_EVENT = DEFINER
        .define("kansokusha.command.health.label.newest-event", "Newest event");

    public static final MessageKey DATABASE_SIZE = DEFINER
        .define("kansokusha.command.health.label.database-size", "Database file size");

    public static final MessageKey WAL_SIZE = DEFINER
        .define("kansokusha.command.health.label.wal-size", "Write-ahead log size");

    public static final MessageKey QUEUE = DEFINER
        .define("kansokusha.command.health.label.queue", "Write queue");

    public static final MessageKey WRITTEN = DEFINER
        .define("kansokusha.command.health.label.written", "Events written since start");

    public static final MessageKey LOST = DEFINER
        .define("kansokusha.command.health.label.lost", "Events lost to write failures");

    public static final MessageKey REJECTED = DEFINER
        .define("kansokusha.command.health.label.rejected", "Events rejected by a full queue");

    public static @UnmodifiableView Map<String, String> defaultMessages() {
        return DEFINER.getCollectedMessages();
    }

    private HealthCommandMessages() {
        throw new UnsupportedOperationException();
    }
}
