package net.okocraft.kansokusha.neoforge;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.event.ClickEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.PermissionSet;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.common.command.EventLocation;
import net.okocraft.kansokusha.common.search.SearchPage;
import net.okocraft.kansokusha.common.search.StoredPayload;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NativeUiContractTest {
    @Test
    void nonPlayerSourceNeedsAdministrativePermission() {
        var source = mock(CommandSourceStack.class);
        when(source.permissions()).thenReturn(PermissionSet.NO_PERMISSIONS);
        assertFalse(NativePermissions.has(source, "kansokusha.command.event"));
        when(source.permissions()).thenReturn(PermissionSet.ALL_PERMISSIONS);
        assertTrue(NativePermissions.has(source, "kansokusha.command.event"));
    }

    @Test
    void coordinatesOfferTeleportOnlyToAuthorizedPlayers() {
        var location = new EventLocation(Optional.of(Key.key("minecraft:overworld")),
            Optional.of(new BlockPosition(7, 70, -4)), Optional.empty());
        var permitted = NativeTeleport.format(location, true, true);
        assertEquals("/kansokusha teleport minecraft:overworld 7 70 -4",
            ((ClickEvent.Payload.Text) permitted.children().getLast().clickEvent().payload()).value());
        assertNull(NativeTeleport.format(location, true, false).children().getLast().clickEvent());
    }

    @Test
    void nativePayloadShowsUsefulFieldsWithoutExposingComponentsAndRejectsPaperBinary() {
        var event = new SearchPage.Event(UUID.fromString("e29b00b1-f941-4bc2-b917-7151f4361b25"),
            Key.key("kansokusha:container_transaction"), Instant.parse("2026-11-02T00:00:00Z"),
            Optional.of(Key.key("okocraft:dqm")), Optional.of(Key.key("minecraft:overworld")), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.of(new StoredPayload(new PayloadGeneration(2), EventPayload.copyOf(
                "{\"amount_delta\":3,\"direction\":\"added_to_container\",\"components\":\"secret\"}"
                    .getBytes(StandardCharsets.UTF_8)))));
        var presenter = new NativeEventPresenter();
        var renderer = NativeEventPresenter.renderer(Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), ZoneOffset.UTC),
            ZoneOffset.UTC).withDebug(false);
        var content = presenter.present(event, true, renderer).orElseThrow();
        var plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
        assertTrue(plain.serialize(content.summary()).contains("added to container x3"));
        assertTrue(plain.serialize(net.okocraft.kansokusha.common.command.EventRenderer.join(content.details()))
            .contains("added_to_container"));
        assertFalse(plain.serialize(net.okocraft.kansokusha.common.command.EventRenderer.join(content.details()))
            .contains("secret"));
        assertFalse(content.sections().containsKey("raw"));

        var paper = new SearchPage.Event(event.eventId(), event.eventType(), event.occurredAt(), event.server(),
            event.world(), event.position(), event.actor(), event.actorName(), event.targetType(), event.searchText(),
            Optional.of(new StoredPayload(new PayloadGeneration(1), EventPayload.copyOf(new byte[]{1, 2, 3}))));
        assertTrue(presenter.present(paper, true, renderer).isEmpty());
    }

    @Test
    void nestedNativePayloadStillRendersVisibleDetails() {
        var event = new SearchPage.Event(UUID.fromString("a1b23031-c290-4fee-b45c-5ae0f8cdd663"),
            Key.key("kansokusha:player_death"), Instant.parse("2026-11-02T00:00:00Z"),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.of(new StoredPayload(new PayloadGeneration(2),
            EventPayload.copyOf("{\"storage\":[{\"id\":\"minecraft:diamond\",\"count\":2,\"components\":\"secret\"}],\"armor\":[],\"offhand\":{\"id\":\"minecraft:shield\"},\"killer\":\"10f2ffde-c7a1-4294-a8ac-a24b7001c189\",\"holder_before\":\"4a2155cc-38e9-4e0f-b4c1-af6dd195d928\"}"
                .getBytes(StandardCharsets.UTF_8)))));
        var content = new NativeEventPresenter().present(event, true,
            NativeEventPresenter.renderer(Clock.systemUTC(), ZoneOffset.UTC).withDebug(false)).orElseThrow();
        var rendered = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(net.okocraft.kansokusha.common.command.EventRenderer.join(content.details()));
        assertTrue(rendered.contains("minecraft:diamond"));
        assertTrue(rendered.contains("minecraft:shield"));
        assertFalse(rendered.contains("secret"));
        assertFalse(rendered.contains("10f2ffde"));
        assertFalse(rendered.contains("4a2155cc"));
    }

    @Test
    void savedBookPagesOpenThroughPermissionCheckedEventSection() {
        var id = UUID.fromString("91efdc63-f89a-4e16-910e-25ca11918e9d");
        var event = new SearchPage.Event(id, Key.key("kansokusha:book_edit"),
            Instant.parse("2026-11-02T00:00:00Z"), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.of(new StoredPayload(new PayloadGeneration(2), EventPayload.copyOf(
                "{\"before\":{\"item\":\"minecraft:writable_book\",\"pages\":[\"Draft\"]},\"after\":{\"item\":\"minecraft:written_book\",\"title\":\"Chronicle\",\"pages\":[\"First page\",\"Second page\"]}}"
                    .getBytes(StandardCharsets.UTF_8)))));
        var content = new NativeEventPresenter().present(event, true,
            NativeEventPresenter.renderer(Clock.systemUTC(), ZoneOffset.UTC).withDebug(false)).orElseThrow();
        var link = content.details().get(1).children().getLast().clickEvent();
        assertEquals("/kansokusha event " + id + " read_after 1",
            ((ClickEvent.Payload.Text) link.payload()).value());
        var pages = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(net.okocraft.kansokusha.common.command.EventRenderer.join(
                content.sections().get("read_after").lines().get()));
        assertTrue(pages.contains("First page"));
        assertTrue(pages.contains("Second page"));
        assertFalse(content.sections().containsKey("raw"));
    }
}
