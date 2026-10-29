package net.okocraft.kansokusha.common.command;

import dev.siroshun.mcmsgdef.MessageKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.common.search.DatabaseHealth;
import net.okocraft.kansokusha.common.search.EventDetail;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.common.search.SearchMetadata;
import net.okocraft.kansokusha.common.search.SearchPage;
import net.okocraft.kansokusha.common.search.SearchRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

class HealthCommandSupportTest {

    private static final Instant OLDEST = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant NEWEST = Instant.parse("2026-09-27T12:30:00Z");

    @Test
    void testHealthIsRendered() {
        var health = new DatabaseHealth(
            new DatabaseHealth.Stored(1234, 56, Optional.of(OLDEST), Optional.of(NEWEST), 5 * 1024 * 1024, 0),
            new DatabaseHealth.Queue(3, 10000, 1200, 0, 7)
        );
        var sent = new ArrayList<Component>();

        HealthCommandSupport.execute(backend(CompletableFuture.completedFuture(health)), sent::add);

        Assertions.assertEquals(List.of(
            HealthCommandMessages.HEADER.asComponent(),
            line(HealthCommandMessages.EVENTS, Component.text(1234L)),
            line(HealthCommandMessages.PLAYER_NAMES, Component.text(56L)),
            line(HealthCommandMessages.OLDEST_EVENT, Component.text("2026-09-01T00:00:00Z")),
            line(HealthCommandMessages.NEWEST_EVENT, Component.text("2026-09-27T12:30:00Z")),
            line(HealthCommandMessages.DATABASE_SIZE, Component.text("5.0 MiB")),
            line(HealthCommandMessages.WAL_SIZE, Component.text("0 B")),
            line(HealthCommandMessages.QUEUE, Component.text("3 / 10000")),
            line(HealthCommandMessages.WRITTEN, Component.text(1200L)),
            line(HealthCommandMessages.LOST, Component.text(0L, NamedTextColor.AQUA)),
            line(HealthCommandMessages.REJECTED, Component.text(7L, NamedTextColor.RED))
        ), sent);
    }

    @Test
    void testEmptyDatabaseShowsPlaceholderForTimes() {
        var health = new DatabaseHealth(
            new DatabaseHealth.Stored(0, 0, Optional.empty(), Optional.empty(), 0, 0),
            new DatabaseHealth.Queue(0, 10, 0, 0, 0)
        );
        var sent = new ArrayList<Component>();

        HealthCommandSupport.execute(backend(CompletableFuture.completedFuture(health)), sent::add);

        Assertions.assertTrue(sent.contains(line(HealthCommandMessages.OLDEST_EVENT, Component.text("-"))));
        Assertions.assertTrue(sent.contains(line(HealthCommandMessages.NEWEST_EVENT, Component.text("-"))));
    }

    @Test
    void testFailureIsReported() {
        var sent = new ArrayList<Component>();

        HealthCommandSupport.execute(
            backend(CompletableFuture.failedFuture(new IllegalStateException("closed"))),
            sent::add
        );

        Assertions.assertEquals(List.of(HealthCommandMessages.FAILED.asComponent()), sent);
    }

    @Test
    void testBytesAreFormattedInBinaryUnits() {
        Assertions.assertEquals("0 B", HealthCommandSupport.formatBytes(0));
        Assertions.assertEquals("1023 B", HealthCommandSupport.formatBytes(1023));
        Assertions.assertEquals("1.0 KiB", HealthCommandSupport.formatBytes(1024));
        Assertions.assertEquals("1.5 MiB", HealthCommandSupport.formatBytes(1536 * 1024));
        Assertions.assertEquals("2.0 GiB", HealthCommandSupport.formatBytes(2L * 1024 * 1024 * 1024));
        Assertions.assertEquals("2048.0 TiB", HealthCommandSupport.formatBytes(2048L * 1024 * 1024 * 1024 * 1024));
    }

    private static Component line(MessageKey label, Component value) {
        return HealthCommandMessages.LINE.apply(label.asComponent(), value);
    }

    private static EventSearchBackend backend(CompletableFuture<DatabaseHealth> health) {
        return new EventSearchBackend() {
            @Override
            public boolean submitSearchable(EventSubmission submission, String searchText) {
                return false;
            }

            @Override
            public boolean submitPlayerLogin(EventSubmission submission, String username) {
                return false;
            }

            @Override
            public CompletableFuture<SearchPage> search(SearchRequest request) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException());
            }

            @Override
            public CompletableFuture<Optional<EventDetail>> findEvent(UUID eventId) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException());
            }

            @Override
            public CompletableFuture<SearchMetadata> searchMetadata() {
                return CompletableFuture.failedFuture(new UnsupportedOperationException());
            }

            @Override
            public CompletableFuture<List<String>> offlinePlayerNames() {
                return CompletableFuture.failedFuture(new UnsupportedOperationException());
            }

            @Override
            public CompletableFuture<DatabaseHealth> health() {
                return health;
            }
        };
    }
}
