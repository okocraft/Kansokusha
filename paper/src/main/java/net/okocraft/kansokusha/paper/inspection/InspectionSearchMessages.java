package net.okocraft.kansokusha.paper.inspection;

import dev.siroshun.mcmsgdef.DefaultMessageDefiner;
import dev.siroshun.mcmsgdef.MessageKey;
import org.jetbrains.annotations.NotNullByDefault;

@NotNullByDefault
public final class InspectionSearchMessages {

    public static final DefaultMessageDefiner DEFINER = DefaultMessageDefiner.create();

    public static final MessageKey HISTORY = DEFINER
        .define("kansokusha.inspect.history", "History at <arg:0>");
    public static final MessageKey NO_HISTORY = DEFINER
        .define("kansokusha.inspect.no-history", "No history found at <arg:0>.");
    public static final MessageKey VIEW_FULL = DEFINER
        .define("kansokusha.inspect.view-full", "[View full history]");

    private InspectionSearchMessages() {
        throw new UnsupportedOperationException();
    }
}
