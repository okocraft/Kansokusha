package net.okocraft.kansokusha.paper.inspection;

import dev.siroshun.mcmsgdef.DefaultMessageDefiner;
import dev.siroshun.mcmsgdef.MessageKey;
import net.kyori.adventure.text.minimessage.translation.Argument;
import org.jetbrains.annotations.NotNullByDefault;

@NotNullByDefault
public final class InspectionSearchMessages {

    public static final DefaultMessageDefiner DEFINER = DefaultMessageDefiner.create();

    public static final MessageKey.Arg1<String> HISTORY = DEFINER
        .define("kansokusha.inspect.history", "History at <position>")
        .with(position -> Argument.string("position", position));
    public static final MessageKey.Arg1<String> NO_HISTORY = DEFINER
        .define("kansokusha.inspect.no-history", "No history found at <position>.")
        .with(position -> Argument.string("position", position));
    public static final MessageKey VIEW_FULL = DEFINER
        .define("kansokusha.inspect.view-full", "[View full history]");

    private InspectionSearchMessages() {
        throw new UnsupportedOperationException();
    }
}
