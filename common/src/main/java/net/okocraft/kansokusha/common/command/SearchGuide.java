package net.okocraft.kansokusha.common.command;

import dev.siroshun.mcmsgdef.DefaultMessageDefiner;
import dev.siroshun.mcmsgdef.MessageKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.okocraft.kansokusha.common.search.query.ActionCategories;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Read-only help: showing a guide never asks the search backend for data. */
@NotNullByDefault
public final class SearchGuide {
    private static final DefaultMessageDefiner DEFINER = DefaultMessageDefiner.create();
    private static final Map<String, MessageKey> LINES = Map.ofEntries(
        Map.entry("header", DEFINER.define("kansokusha.command.search.guide.header", "<gray>Search guide — click an example to fill the command line</gray>")),
        Map.entry("open", DEFINER.define("kansokusha.command.search.guide.open", "<gray>[Search guide]</gray>")),
        Map.entry("user", DEFINER.define("kansokusha.command.search.guide.user", "<gray>user Alice — player name</gray>")),
        Map.entry("action", DEFINER.define("kansokusha.command.search.guide.action", "<gray>action … — event or category</gray>")),
        Map.entry("time", DEFINER.define("kansokusha.command.search.guide.time", "<gray>time 1d — recent duration; today / yesterday</gray>")),
        Map.entry("radius", DEFINER.define("kansokusha.command.search.guide.radius", "<gray>radius 10 — within 10 blocks of your current X/Z</gray>")),
        Map.entry("around", DEFINER.define("kansokusha.command.search.guide.around", "<gray>around minecraft:overworld 0 0 10 — X/Z center and radius</gray>")),
        Map.entry("position", DEFINER.define("kansokusha.command.search.guide.position", "<gray>position minecraft:overworld 0 64 0 — exact block</gray>")),
        Map.entry("world", DEFINER.define("kansokusha.command.search.guide.world", "<gray>world minecraft:overworld — world key</gray>")),
        Map.entry("target", DEFINER.define("kansokusha.command.search.guide.target", "<gray>target minecraft:stone — block / item / entity type</gray>")),
        Map.entry("filter", DEFINER.define("kansokusha.command.search.guide.filter", "<gray>filter hello — recorded text contains hello</gray>")),
        Map.entry("exclude", DEFINER.define("kansokusha.command.search.guide.exclude", "<gray>exclude user Alice — omit matching events</gray>")),
        Map.entry("order", DEFINER.define("kansokusha.command.search.guide.order", "<gray>order oldest — oldest first; default newest</gray>")),
        Map.entry("limit", DEFINER.define("kansokusha.command.search.guide.limit", "<gray>limit 20 — results per page</gray>")),
        Map.entry("example-user", DEFINER.define("kansokusha.command.search.guide.example-user", "<gray>/kansokusha search user Alice time 3h</gray>")),
        Map.entry("example-block", DEFINER.define("kansokusha.command.search.guide.example-block", "<gray>/kansokusha search radius 10 time 1d action block</gray>")),
        Map.entry("example-around", DEFINER.define("kansokusha.command.search.guide.example-around", "<gray>/kansokusha search around minecraft:overworld 0 0 10 time 1d action block</gray>")),
        Map.entry("example-container", DEFINER.define("kansokusha.command.search.guide.example-container", "<gray>/kansokusha search action container time 1d</gray>")),
        Map.entry("example-chat", DEFINER.define("kansokusha.command.search.guide.example-chat", "<gray>/kansokusha search action chat user Alice time 1d</gray>"))
    );

    public static Map<String, String> defaultMessages() { return DEFINER.getCollectedMessages(); }

    public static Component link() {
        return LINES.get("open").asComponent().clickEvent(ClickEvent.runCommand("/kansokusha search"));
    }

    public static void send(Predicate<String> permission, Consumer<Component> send, boolean radiusAvailable) {
        send.accept(LINES.get("header").asComponent());
        for (var name : java.util.List.of("user", "action", "time", "radius", "around", "position", "world",
            "target", "filter", "exclude", "order", "limit")) {
            if (!name.equals("radius") || radiusAvailable) send.accept(LINES.get(name).asComponent());
        }
        example(send, "user");
        if (visible(permission, "block")) example(send, radiusAvailable ? "block" : "around");
        if (visible(permission, "container")) example(send, "container");
        if (visible(permission, "chat")) example(send, "chat");
    }

    private static boolean visible(Predicate<String> permission, String category) {
        return ActionCategories.events(category).stream().anyMatch(type -> permission.test(SearchCommandSupport.eventPermission(type)));
    }

    private static void example(Consumer<Component> send, String name) {
        var command = switch (name) {
            case "block" -> "/kansokusha search radius 10 time 1d action block";
            case "around" -> "/kansokusha search around minecraft:overworld 0 0 10 time 1d action block";
            case "container" -> "/kansokusha search action container time 1d";
            case "chat" -> "/kansokusha search action chat user Alice time 1d";
            default -> "/kansokusha search user Alice time 3h";
        };
        send.accept(LINES.get("example-" + name).asComponent().clickEvent(ClickEvent.suggestCommand(command)));
    }

    private SearchGuide() { }
}
