package net.okocraft.kansokusha.paper.command;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.minecraft.nbt.CompoundTag;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.common.search.EventDetail;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.common.search.SearchPage;
import net.okocraft.kansokusha.common.search.StoredPayload;
import net.okocraft.kansokusha.paper.builtin.PaperItemStackPayloadCodec;
import net.okocraft.kansokusha.paper.builtin.PaperPayloadNbtCodec;
import net.okocraft.kansokusha.paper.testsupport.CommandTester;
import net.okocraft.kansokusha.paper.testsupport.TestSources;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

class ReadBookCommandTest {

    @Test
    void testReadRouteSchedulesBookUiOnPlayerRegionAndDoesNotGiveItems() throws Exception {
        var api = Mockito.mock(EventSearchBackend.class);
        var plugin = Mockito.mock(Plugin.class);
        var player = Mockito.mock(Player.class);
        Mockito.when(player.hasPermission(Mockito.anyString())).thenReturn(true);
        var scheduler = Mockito.mock(EntityScheduler.class);
        Mockito.when(player.getScheduler()).thenReturn(scheduler);
        Mockito.when(scheduler.execute(Mockito.eq(plugin), Mockito.any(), Mockito.isNull(), Mockito.eq(1L)))
            .thenAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return true; });
        var detail = book();
        Mockito.when(api.findEvent(detail.event().eventId())).thenReturn(CompletableFuture.completedFuture(Optional.of(detail)));
        var tester = CommandTester.of(EventCommand.createEventCommand(api, Clock.systemUTC(), ZoneOffset.UTC, plugin).build());
        Assertions.assertEquals(1, tester.execute(TestSources.of(player), "event " + detail.event().eventId() + " read after"));
        var opened = ArgumentCaptor.forClass(ItemStack.class);
        Mockito.verify(player).openBook(opened.capture());
        Assertions.assertTrue(((BookMeta) opened.getValue().getItemMeta()).pages().getFirst().toString().contains("Archived page"));
        Mockito.verify(player, Mockito.never()).getInventory();
    }

    @Test
    void testPermissionLossBeforeUiExecutionPreventsOpening() {
        var api = Mockito.mock(EventSearchBackend.class);
        var plugin = Mockito.mock(Plugin.class);
        var player = Mockito.mock(Player.class);
        Mockito.when(player.hasPermission(Mockito.anyString())).thenReturn(true);
        var scheduler = Mockito.mock(EntityScheduler.class);
        Mockito.when(player.getScheduler()).thenReturn(scheduler);
        var task = ArgumentCaptor.forClass(Runnable.class);
        Mockito.when(scheduler.execute(Mockito.eq(plugin), task.capture(), Mockito.isNull(), Mockito.eq(1L))).thenReturn(true);
        var detail = book();
        Mockito.when(api.findEvent(detail.event().eventId())).thenReturn(CompletableFuture.completedFuture(Optional.of(detail)));
        Assertions.assertTrue(ReadBookCommand.execute(api, plugin, player, detail.event().eventId().toString(), "after"));
        Mockito.when(player.hasPermission(Mockito.anyString())).thenReturn(false);
        task.getValue().run();
        Mockito.verify(player, Mockito.never()).openBook(Mockito.any(ItemStack.class));
    }

    private static EventDetail book() {
        var item = ItemStack.of(Material.WRITTEN_BOOK);
        var meta = (BookMeta) item.getItemMeta();
        meta.setTitle("Archive");
        meta.setAuthor("Alice");
        meta.pages(Component.text("Archived page"));
        item.setItemMeta(meta);
        var payload = new CompoundTag();
        payload.put("after", PaperItemStackPayloadCodec.encode(item));
        var event = new SearchPage.Event(UUID.randomUUID(), Key.key("kansokusha:lectern_change"), Instant.now(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.of(new StoredPayload(PayloadGeneration.FIRST, PaperPayloadNbtCodec.encode(payload))));
        return new EventDetail(event, PayloadGeneration.FIRST, Instant.now().plusSeconds(60));
    }
}
