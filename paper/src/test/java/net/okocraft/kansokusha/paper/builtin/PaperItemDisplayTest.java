package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.text.Component;
import net.minecraft.nbt.CompoundTag;
import net.okocraft.kansokusha.common.language.LanguageProvider;
import net.okocraft.kansokusha.paper.command.KansokushaCommands;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Locale;

class PaperItemDisplayTest {

    private org.mockito.MockedStatic<org.bukkit.Bukkit> itemFactory;

    @org.junit.jupiter.api.BeforeEach
    void installItemFactory() {
        this.itemFactory = PaperItemViewTestSupport.install();
    }

    @AfterEach
    void cleanup() {
        LanguageProvider.unload();
        this.itemFactory.close();
    }

    @Test
    void testQuantityAndTransferAttempt(@TempDir Path dir) throws Exception {
        LanguageProvider.load(dir, KansokushaCommands.getDefaultMessages());
        var payload = new CompoundTag();
        payload.put("item", PaperItemStackPayloadCodec.encode(ItemStack.of(Material.DIAMOND, 12)));
        var renderer = PaperEventPresenter.renderer(Clock.systemUTC(), ZoneOffset.UTC);
        var summary = new PaperEventPresenter().present(PaperEventPresenterTest.event("container_transfer", payload),
            false, renderer).orElseThrow().summary();
        var text = PaperEventPresenterTest.plain(summary, Locale.ENGLISH);
        Assertions.assertTrue(text.contains("transfer"), text);
        Assertions.assertTrue(text.contains("×12"), text);
        var delta = new CompoundTag();
        delta.putString("transfer_direction", "removed_from_container");
        delta.putInt("amount_delta", -12);
        var confirmed = new PaperEventPresenter().present(PaperEventPresenterTest.event("container_transaction", delta),
            false, renderer).orElseThrow().summary();
        Assertions.assertTrue(PaperEventPresenterTest.plain(confirmed, Locale.ENGLISH).contains("x12"));
    }

    @Test
    void testBookAttributesAndEveryPageRemainInDetails(@TempDir Path dir) throws Exception {
        LanguageProvider.load(dir, KansokushaCommands.getDefaultMessages());
        var book = ItemStack.of(Material.WRITTEN_BOOK, 1);
        var meta = (BookMeta) book.getItemMeta();
        meta.setTitle("Investigation");
        meta.setAuthor("Alice");
        meta.addPages(Component.text("first page"), Component.text("last page " + "長".repeat(400)));
        book.setItemMeta(meta);
        var payload = new CompoundTag();
        payload.put("new_book_meta", PaperItemStackPayloadCodec.encode(book));
        var lines = PaperPayloadDisplay.details(payload).stream()
            .map(line -> PaperEventPresenterTest.plain(line, Locale.ENGLISH)).toList();
        Assertions.assertTrue(lines.stream().anyMatch(line -> line.contains("Investigation")), lines.toString());
        Assertions.assertTrue(lines.stream().anyMatch(line -> line.contains("Alice")), lines.toString());
        Assertions.assertTrue(lines.stream().anyMatch(line -> line.contains("first page")), lines.toString());
        Assertions.assertTrue(lines.stream().anyMatch(line -> line.contains("last page")), lines.toString());
        Assertions.assertEquals(400, String.join("", lines).chars().filter(c -> c == '長').count());
    }
}
