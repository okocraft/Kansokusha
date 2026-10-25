package net.okocraft.kansokusha.common.search.query;

import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Shared built-in action aliases. Namespaced event names always remain literal. */
@NotNullByDefault
public final class ActionCategories {
    private static final Map<String, Set<Key>> CATEGORIES = categories();

    public static Set<Key> events(String name) {
        return CATEGORIES.getOrDefault(name, Set.of());
    }

    public static List<String> names() {
        return List.copyOf(CATEGORIES.keySet());
    }

    private static Map<String, Set<Key>> categories() {
        var result = new LinkedHashMap<String, Set<Key>>();
        add(result, "block", "block_break", "block_place");
        add(result, "-block", "block_break");
        add(result, "+block", "block_place");
        add(result, "container", "container_transaction", "container_pickup", "container_transfer");
        add(result, "item", "item_drop", "item_pickup");
        add(result, "chat", "paper_chat", "velocity_chat");
        add(result, "command", "paper_player_command", "paper_server_command", "velocity_command");
        add(result, "session", "paper_join", "paper_quit", "paper_kick", "velocity_post_login",
            "velocity_disconnect", "server_connected", "backend_kick");
        add(result, "death", "player_death");
        add(result, "kill", "entity_death");
        return java.util.Collections.unmodifiableMap(result);
    }

    private static void add(Map<String, Set<Key>> categories, String name, String... events) {
        categories.put(name, java.util.Arrays.stream(events)
            .map(event -> Key.key("kansokusha", event)).collect(Collectors.toUnmodifiableSet()));
    }

    private ActionCategories() { }
}
