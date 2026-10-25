package net.okocraft.kansokusha.paper.builtin;

import dev.siroshun.mcmsgdef.DefaultMessageDefiner;
import dev.siroshun.mcmsgdef.MessageKey;

import java.util.Map;

public final class PaperViewMessages {

    private static final DefaultMessageDefiner DEFINER = DefaultMessageDefiner.create();
    public static final MessageKey ADDED = DEFINER.define("kansokusha.display.compact.added", "<green>+</green>");
    public static final MessageKey REMOVED = DEFINER.define("kansokusha.display.compact.removed", "<red>-</red>");
    public static final MessageKey OPERATION_SCOPE = DEFINER.define("kansokusha.display.compact.operation-scope",
        "Recorded operation; completion or the final result may differ. Container deltas are committed net changes.");
    public static final MessageKey READ = DEFINER.define("kansokusha.display.compact.read", "[Read]");
    public static final MessageKey ITEMS = DEFINER.define("kansokusha.display.compact.items", "[Items]");
    public static final MessageKey EMPTY_ITEM = DEFINER.define("kansokusha.display.compact.empty-item", "(empty)");
    public static final MessageKey BOOK_UNAVAILABLE = DEFINER.define("kansokusha.display.compact.book-unavailable", "<red>This book snapshot is unavailable.</red>");
    public static final MessageKey PLAYER_ONLY = DEFINER.define("kansokusha.display.compact.book-player-only", "<red>Read books in-game. Use the saved-data view from the console.</red>");

    public static Map<String, String> defaultMessages() {
        return DEFINER.getCollectedMessages();
    }

    private PaperViewMessages() {
    }
}
