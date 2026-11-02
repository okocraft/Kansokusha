package net.okocraft.kansokusha.neoforge;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.common.config.KansokushaConfig;
import net.okocraft.kansokusha.common.runtime.KansokushaRuntime;
import net.okocraft.kansokusha.common.search.SearchRequest;
import net.okocraft.kansokusha.common.search.query.SearchQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NativeEventContractTest {
    @Test
    void nativeRecordPersistsAcrossReopenAndRendersItsSavedState(@TempDir Path directory) throws Exception {
        var eventType = Key.key("kansokusha:block_break");
        var server = Key.key("okocraft:dqm");
        var world = Key.key("minecraft:overworld");
        var actor = new PlayerActor(UUID.fromString("23ca28b0-7c42-4c1f-91ed-43e667f82d21"));
        var payload = EventPayload.copyOf("{\"before\":\"minecraft:stone\",\"after\":\"minecraft:air\"}"
            .getBytes(StandardCharsets.UTF_8));
        var config = new KansokushaConfig(Optional.of(server), 100, 10, Duration.ofSeconds(1),
            Duration.ofHours(1), Duration.ofDays(1), ZoneOffset.UTC,
            new KansokushaConfig.Retention(Map.of(), Duration.ofDays(30), Set.of()),
            new KansokushaConfig.DuckDb(Optional.of("512MB"), 2));

        try (var runtime = KansokushaRuntime.start(directory, config, server, ignored -> { }, (message, failure) -> {
            throw new AssertionError(message, failure);
        })) {
            runtime.registerEventType(new EventTypeDefinition(eventType, new PayloadGeneration(2)));
            assertTrue(runtime.submit(new EventSubmission(eventType, new PayloadGeneration(2), Instant.now(),
                server, world, new BlockPosition(12, 70, -8), actor, Key.key("minecraft:stone"), payload)));
        }

        try (var reopened = KansokushaRuntime.start(directory, config, server, ignored -> { },
            (message, failure) -> { throw new AssertionError(message, failure); })) {
            var request = new SearchRequest(new SearchQuery(SearchQuery.Conditions.empty(),
                SearchQuery.Conditions.empty(), SearchQuery.Order.NEWEST, OptionalInt.empty()),
                new SearchRequest.Constraints(Set.of(eventType)), Optional.empty(), 10);
            var page = reopened.search(request).join();
            assertEquals(1, page.events().size());
            var saved = page.events().getFirst();
            assertEquals(eventType, saved.eventType());
            assertEquals(Optional.of(server), saved.server());
            assertEquals(Optional.of(world), saved.world());
            assertEquals(Optional.of(new BlockPosition(12, 70, -8)), saved.position());
            assertEquals(Optional.of(actor), saved.actor());
            assertEquals(2, saved.payload().orElseThrow().generation().value());
            assertArrayEquals(payload.copyBytes(), saved.payload().orElseThrow().bytes().copyBytes());

            var details = new net.okocraft.kansokusha.common.command.EventRenderer(
                Clock.systemUTC(), ZoneOffset.UTC, new NativeEventPresenter(),
                (key, kind) -> net.kyori.adventure.text.Component.text(key.asString()))
                .detail(reopened.findEvent(saved.eventId()).join().orElseThrow());
            var rendered = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(net.okocraft.kansokusha.common.command.EventRenderer.join(details));
            assertTrue(rendered.contains("minecraft:stone"));
            assertTrue(rendered.contains("minecraft:air"));
        }
    }
}
