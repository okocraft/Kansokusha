package net.okocraft.kansokusha.paper.inspection;

import dev.siroshun.mcmsgdef.DefaultMessageDefiner;
import dev.siroshun.mcmsgdef.MessageKey;
import net.kyori.adventure.text.minimessage.translation.Argument;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.Map;

@NotNullByDefault
public final class InspectionSearchMessages {

    private static final DefaultMessageDefiner DEFINER = DefaultMessageDefiner.create();

    public static final MessageKey.Arg1<String> HISTORY = DEFINER
        .define("kansokusha.inspect.history", "<gold>History at <aqua><position></aqua></gold>")
        .with(position -> Argument.string("position", position));

    public static final MessageKey.Arg1<String> NO_HISTORY = DEFINER
        .define(
            "kansokusha.inspect.no-history",
            "<gray>No visible history was found at <aqua><position></aqua>.</gray>"
        )
        .with(position -> Argument.string("position", position));

    public static final MessageKey VIEW_FULL = DEFINER
        .define("kansokusha.inspect.view-full", "<gold>[View full history]</gold>");

    public static @UnmodifiableView Map<String, String> defaultMessages() {
        return DEFINER.getCollectedMessages();
    }

    private InspectionSearchMessages() {
        throw new UnsupportedOperationException();
    }
}
