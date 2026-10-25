package net.okocraft.kansokusha.common.command;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Last displayed page per connected sender; never persisted across plugin lifetimes. */
@NotNullByDefault
public final class SearchNavigation {
    private static final ConcurrentMap<Object, String> PAGES = new ConcurrentHashMap<>();

    public static void remember(@Nullable Object sender, String command) {
        if (sender != null) PAGES.put(sender, command);
    }

    public static Optional<String> last(@Nullable Object sender) {
        return sender == null ? Optional.empty() : Optional.ofNullable(PAGES.get(sender));
    }

    public static void forget(Object sender) { PAGES.remove(sender); }
    public static void clear() { PAGES.clear(); }
    private SearchNavigation() { }
}
