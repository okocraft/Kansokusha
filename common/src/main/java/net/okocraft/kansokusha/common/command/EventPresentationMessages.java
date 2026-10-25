package net.okocraft.kansokusha.common.command;

import dev.siroshun.mcmsgdef.DefaultMessageDefiner;
import dev.siroshun.mcmsgdef.MessageKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.translation.Argument;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.LinkedHashMap;
import java.util.Map;

@NotNullByDefault
public final class EventPresentationMessages {

    private static final DefaultMessageDefiner DEFINER = DefaultMessageDefiner.create();
    public static final MessageKey NOW = DEFINER.define("kansokusha.display.time.now", "just now");
    public static final MessageKey SOON = DEFINER.define("kansokusha.display.time.soon", "in less than a minute");
    public static final MessageKey UNAVAILABLE = DEFINER.define("kansokusha.display.unavailable",
        "Payload unavailable or unsupported; showing common fields");
    public static final MessageKey NO_ACTOR = DEFINER.define("kansokusha.display.no-actor", "(actor not recorded)");
    public static final MessageKey MORE_IN_DETAILS = DEFINER.define("kansokusha.display.more-in-details", "(payload omitted; see details)");
    private static final Map<String, MessageKey.Arg1<Long>> RELATIVE = new LinkedHashMap<>();

    static {
        for (var unit : new String[]{"minutes", "hours", "days"}) {
            for (var future : new boolean[]{false, true}) {
                var key = unit + (future ? ".future" : ".past");
                RELATIVE.put(key, DEFINER.define("kansokusha.display.time." + key,
                    future ? "in <amount> " + unit : "<amount> " + unit + " ago")
                    .with((Long amount) -> Argument.numeric("amount", amount)));
                RELATIVE.put(key + ".one", DEFINER.define("kansokusha.display.time." + key + ".one",
                    future ? "in <amount> " + unit.substring(0, unit.length() - 1)
                        : "<amount> " + unit.substring(0, unit.length() - 1) + " ago")
                    .with((Long amount) -> Argument.numeric("amount", amount)));
            }
        }
    }

    public static MessageKey.Arg1<Long> relative(String unit, boolean future, long amount) {
        return RELATIVE.get(unit + (future ? ".future" : ".past") + (amount == 1 ? ".one" : ""));
    }

    public static final MessageKey.Arg2<Component, Component> ROW = DEFINER
        .define("kansokusha.display.row", "<dark_gray>[<time>]</dark_gray> <summary>")
        .with(time -> Argument.component("time", time), summary -> Argument.component("summary", summary));

    public static final MessageKey.Arg1<Component> TARGET = DEFINER
        .define("kansokusha.display.compact.target", "<aqua><target></aqua>")
        .with(target -> Argument.component("target", target));
    public static final MessageKey.Arg2<Component, Component> FIELD = DEFINER
        .define("kansokusha.display.compact.field", "<gray><label>: </gray><value>")
        .with(label -> Argument.component("label", label), value -> Argument.component("value", value));
    public static final MessageKey HOVER_HINT = DEFINER.define("kansokusha.display.compact.hover-hint", "Hover for context");
    public static final MessageKey CONSOLE_HINT = DEFINER.define("kansokusha.display.compact.console-hint",
        "Open an event with /kansokusha event ID; append record or a text view to expand it.");
    public static final MessageKey EMPTY = DEFINER.define("kansokusha.display.compact.empty", "(empty)");
    public static final MessageKey EXTRA = DEFINER.define("kansokusha.display.compact.extra", "[Additional data]");
    public static final MessageKey HINT = DEFINER.define("kansokusha.display.compact.hint",
        "Hover for context; click a row for details");
    public static final MessageKey RECORD = DEFINER.define("kansokusha.display.compact.record", "[Record information]");
    public static final MessageKey BACK = DEFINER.define("kansokusha.display.compact.back", "[Back to results]");
    public static final MessageKey FULL = DEFINER.define("kansokusha.display.compact.full", "[Full text]");
    public static final MessageKey RAW = DEFINER.define("kansokusha.display.compact.raw", "[Saved data]");
    public static final MessageKey OVERVIEW = DEFINER.define("kansokusha.display.compact.overview", "[Overview]");
    public static final MessageKey INVALID_VIEW = DEFINER.define("kansokusha.display.compact.invalid-view",
        "<red>This view or page is unavailable. Open the event overview again.</red>");
    public static final MessageKey LOCATION = DEFINER.define("kansokusha.display.compact.location", "Location");
    public static final MessageKey SUMMARY = DEFINER.define("kansokusha.display.compact.summary", "Summary");
    public static final MessageKey.Arg1<Component> SCOPE = DEFINER
        .define("kansokusha.display.compact.scope", "<gray>History: <location></gray>")
        .with(location -> Argument.component("location", location));
    public static final MessageKey.Arg2<Integer, Integer> PAGE = DEFINER
        .define("kansokusha.display.compact.page", "<gray><page>/<pages></gray>")
        .with(page -> Argument.numeric("page", page), pages -> Argument.numeric("pages", pages));

    public static Map<String, String> defaultMessages() {
        return DEFINER.getCollectedMessages();
    }

    private EventPresentationMessages() {
        throw new UnsupportedOperationException();
    }
}
