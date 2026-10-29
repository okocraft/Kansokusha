package net.okocraft.kansokusha.common.command;

import dev.siroshun.mcmsgdef.MessageKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.okocraft.kansokusha.common.search.DatabaseHealth;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Implements {@code /kansokusha health}, which shows the state of the event database.
 */
@NotNullByDefault
public final class HealthCommandSupport {

    public static final String PERMISSION = "kansokusha.command.health";

    private static final String[] BINARY_UNITS = {"KiB", "MiB", "GiB", "TiB"};

    public static void execute(EventSearchBackend backend, Consumer<Component> sendMessage) {
        backend.health().whenComplete((health, failure) -> {
            if (failure != null) {
                sendMessage.accept(HealthCommandMessages.FAILED.asComponent());
                return;
            }
            render(health).forEach(sendMessage);
        });
    }

    static List<Component> render(DatabaseHealth health) {
        var stored = health.stored();
        var queue = health.queue();
        return List.of(
            HealthCommandMessages.HEADER.asComponent(),
            line(HealthCommandMessages.EVENTS, Component.text(stored.events())),
            line(HealthCommandMessages.PLAYER_NAMES, Component.text(stored.playerNames())),
            line(HealthCommandMessages.OLDEST_EVENT, Component.text(formatInstant(stored.oldestEvent()))),
            line(HealthCommandMessages.NEWEST_EVENT, Component.text(formatInstant(stored.newestEvent()))),
            line(HealthCommandMessages.DATABASE_SIZE, Component.text(formatBytes(stored.databaseBytes()))),
            line(HealthCommandMessages.WAL_SIZE, Component.text(formatBytes(stored.walBytes()))),
            line(HealthCommandMessages.QUEUE, Component.text(queue.queued() + " / " + queue.capacity())),
            line(HealthCommandMessages.WRITTEN, Component.text(queue.written())),
            line(HealthCommandMessages.LOST, warning(queue.lost())),
            line(HealthCommandMessages.REJECTED, warning(queue.rejected()))
        );
    }

    private static Component line(MessageKey label, Component value) {
        return HealthCommandMessages.LINE.apply(label.asComponent(), value);
    }

    // Losing or refusing events is the failure an administrator needs to notice.
    private static Component warning(long count) {
        return Component.text(count, count > 0 ? NamedTextColor.RED : NamedTextColor.AQUA);
    }

    static String formatInstant(Optional<Instant> instant) {
        return instant.map(Instant::toString).orElse("-");
    }

    static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        var value = (double) bytes;
        var unit = -1;
        while (value >= 1024 && unit < BINARY_UNITS.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, BINARY_UNITS[unit]);
    }

    private HealthCommandSupport() {
        throw new UnsupportedOperationException();
    }
}
