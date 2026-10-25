package net.okocraft.kansokusha.common.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Time is sampled when a response is rendered, never when the command starts. */
@ApiStatus.Internal
@NotNullByDefault
public final class EventTime {

    private static final DateTimeFormatter ABSOLUTE =
        DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS XXX '['VV']'");

    public static Component absolute(Instant instant, ZoneId timezone) {
        return Component.text(ABSOLUTE.withZone(timezone).format(instant));
    }

    public static Component relative(Instant occurredAt, Instant now, ZoneId timezone) {
        var future = occurredAt.isAfter(now);
        var seconds = Duration.between(future ? now : occurredAt, future ? occurredAt : now).getSeconds();
        Component text;
        if (seconds < 60) {
            text = (future ? EventPresentationMessages.SOON : EventPresentationMessages.NOW).asComponent();
        } else {
            var unit = seconds < 3600 ? "minutes" : seconds < 86400 ? "hours" : "days";
            var amount = seconds / (seconds < 3600 ? 60 : seconds < 86400 ? 3600 : 86400);
            text = EventPresentationMessages.relative(unit, future, amount).apply(amount);
        }
        return text.hoverEvent(HoverEvent.showText(absolute(occurredAt, timezone)));
    }

    private EventTime() {
        throw new UnsupportedOperationException();
    }
}
