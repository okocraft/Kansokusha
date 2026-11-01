package net.okocraft.kansokusha.common.command;

import dev.siroshun.mcmsgdef.DefaultMessageDefiner;
import dev.siroshun.mcmsgdef.MessageKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.translation.Argument;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.Map;

@NotNullByDefault
public final class RetentionCommandMessages {

    private static final DefaultMessageDefiner DEFINER = DefaultMessageDefiner.create();

    public static final MessageKey PREVIEW_HEADER = DEFINER
        .define(
            "kansokusha.command.admin.retention.preview.header",
            "<gold>Recalculate the expiry of stored events from the current retention settings</gold>"
        );

    public static final MessageKey.Arg3<String, Component, Integer> PREVIEW_POLICY = DEFINER
        .define(
            "kansokusha.command.admin.retention.preview.policy",
            "<gray><aqua><duration></aqua><dark_gray> / </dark_gray><after><dark_gray>: </dark_gray><aqua><count></aqua> event types</gray>"
        )
        .with(
            duration -> Argument.string("duration", duration),
            after -> Argument.component("after", after),
            count -> Argument.numeric("count", count)
        );

    public static final MessageKey.Arg1<String> PREVIEW_DEFAULT = DEFINER
        .define(
            "kansokusha.command.admin.retention.preview.default",
            "<gray><aqua><duration></aqua><dark_gray> / </dark_gray>deleted<dark_gray>: </dark_gray>other event types</gray>"
        )
        .with(duration -> Argument.string("duration", duration));

    public static final MessageKey AFTER_ARCHIVED = DEFINER
        .define("kansokusha.command.admin.retention.after.archived", "archived");

    public static final MessageKey AFTER_DELETED = DEFINER
        .define("kansokusha.command.admin.retention.after.deleted", "deleted");

    public static final MessageKey PREVIEW_WARNING = DEFINER
        .define(
            "kansokusha.command.admin.retention.preview.warning",
            "<yellow>Events that become expired are deleted by the next cleanup, or archived by the next archive run. This cannot be undone.</yellow>"
        );

    public static final MessageKey PREVIEW_CONFIRM = DEFINER
        .define(
            "kansokusha.command.admin.retention.preview.confirm",
            "<gray>Run <aqua>/kansokusha admin retention apply confirm</aqua> to apply.</gray>"
        );

    public static final MessageKey STARTED = DEFINER
        .define(
            "kansokusha.command.admin.retention.started",
            "<gray>Applying the retention settings to stored events. This can take a while on a large database.</gray>"
        );

    public static final MessageKey.Arg1<Long> COMPLETED = DEFINER
        .define(
            "kansokusha.command.admin.retention.completed",
            "<gray>Applied the retention settings: the expiry of <aqua><count></aqua> events changed.</gray>"
        )
        .with(count -> Argument.numeric("count", count));

    public static final MessageKey ALREADY_RUNNING = DEFINER
        .define(
            "kansokusha.command.admin.retention.already-running",
            "<red>The retention settings are already being applied.</red>"
        );

    public static final MessageKey FAILED = DEFINER
        .define(
            "kansokusha.command.admin.retention.failed",
            "<red>Failed to apply the retention settings. Check the server log.</red>"
        );

    public static @UnmodifiableView Map<String, String> defaultMessages() {
        return DEFINER.getCollectedMessages();
    }

    private RetentionCommandMessages() {
        throw new UnsupportedOperationException();
    }
}
