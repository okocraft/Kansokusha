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
    private static final Map<String, MessageKey.Arg1<Long>> RELATIVE = new LinkedHashMap<>();

    static {
        for (var unit : new String[]{"minutes", "hours", "days"}) {
            for (var future : new boolean[]{false, true}) {
                var key = unit + (future ? ".future" : ".past");
                RELATIVE.put(key, DEFINER.define("kansokusha.display.time." + key,
                    future ? "in <amount> " + unit : "<amount> " + unit + " ago")
                    .with((Long amount) -> Argument.numeric("amount", amount)));
            }
        }
    }

    public static MessageKey.Arg1<Long> relative(String unit, boolean future) {
        return RELATIVE.get(unit + (future ? ".future" : ".past"));
    }

    public static final MessageKey.Arg2<Component, Component> ROW = DEFINER
        .define("kansokusha.display.row", "<dark_gray>[<time>]</dark_gray> <summary>")
        .with(time -> Argument.component("time", time), summary -> Argument.component("summary", summary));

    public static Map<String, String> defaultMessages() {
        return DEFINER.getCollectedMessages();
    }

    private EventPresentationMessages() {
        throw new UnsupportedOperationException();
    }
}
