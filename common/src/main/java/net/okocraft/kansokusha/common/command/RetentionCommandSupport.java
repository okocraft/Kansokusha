package net.okocraft.kansokusha.common.command;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.okocraft.kansokusha.common.config.KansokushaConfig;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.common.search.RetentionAlreadyRunningException;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * Implements {@code /kansokusha admin retention apply}, which recalculates the expiry of stored events
 * from the retention settings the runtime was started with.
 */
@NotNullByDefault
public final class RetentionCommandSupport {

    public static final String PERMISSION = "kansokusha.command.admin.retention";

    /** Shows what {@link #apply} would do without touching the database. */
    public static void preview(EventSearchBackend backend, Consumer<Component> sendMessage) {
        render(backend.retention()).forEach(sendMessage);
    }

    public static void apply(EventSearchBackend backend, Consumer<Component> sendMessage) {
        sendMessage.accept(RetentionCommandMessages.STARTED.asComponent());
        backend.applyRetention().whenComplete((updated, failure) -> {
            if (failure == null) {
                sendMessage.accept(RetentionCommandMessages.COMPLETED.apply(updated));
            } else if (unwrap(failure) instanceof RetentionAlreadyRunningException) {
                sendMessage.accept(RetentionCommandMessages.ALREADY_RUNNING.asComponent());
            } else {
                sendMessage.accept(RetentionCommandMessages.FAILED.asComponent());
            }
        });
    }

    static List<Component> render(KansokushaConfig.Retention retention) {
        // Longest first, and archived before deleted for the same period.
        var groups = new TreeMap<Group, Integer>(
            Comparator.comparing(Group::duration).reversed().thenComparing(Group::archived, Comparator.reverseOrder())
        );
        for (Map.Entry<Key, Duration> entry : retention.durations().entrySet()) {
            groups.merge(
                new Group(entry.getValue(), retention.archivedEventTypes().contains(entry.getKey())),
                1,
                Integer::sum
            );
        }

        var lines = new ArrayList<Component>();
        lines.add(RetentionCommandMessages.PREVIEW_HEADER.asComponent());
        groups.forEach((group, count) -> lines.add(RetentionCommandMessages.PREVIEW_POLICY.apply(
            formatDuration(group.duration()),
            (group.archived() ? RetentionCommandMessages.AFTER_ARCHIVED : RetentionCommandMessages.AFTER_DELETED)
                .asComponent(),
            count
        )));
        lines.add(RetentionCommandMessages.PREVIEW_DEFAULT.apply(formatDuration(retention.defaultDuration())));
        lines.add(RetentionCommandMessages.PREVIEW_WARNING.asComponent());
        lines.add(RetentionCommandMessages.PREVIEW_CONFIRM.asComponent());
        return lines;
    }

    // ISO-8601 like the configuration, but in days when it is whole days (Duration prints P180D as PT4320H).
    static String formatDuration(Duration duration) {
        return duration.toSeconds() % Duration.ofDays(1).toSeconds() == 0 && duration.getNano() == 0
            ? "P" + duration.toDays() + "D"
            : duration.toString();
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
    }

    private record Group(Duration duration, boolean archived) {
    }

    private RetentionCommandSupport() {
        throw new UnsupportedOperationException();
    }
}
