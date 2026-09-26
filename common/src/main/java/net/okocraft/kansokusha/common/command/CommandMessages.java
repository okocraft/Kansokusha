package net.okocraft.kansokusha.common.command;

import dev.siroshun.mcmsgdef.DefaultMessageDefiner;
import dev.siroshun.mcmsgdef.MessageKey;
import net.kyori.adventure.text.minimessage.translation.Argument;

public final class CommandMessages {

    public static final DefaultMessageDefiner DEFINER = DefaultMessageDefiner.create();

    public static final MessageKey.Arg1<String> VERSION_PRINT = DEFINER
        .define("kansokusha.command.version.print", "Kansokusha <version>")
        .with(version -> Argument.string("version", version));

    private CommandMessages() {
        throw new UnsupportedOperationException();
    }
}
