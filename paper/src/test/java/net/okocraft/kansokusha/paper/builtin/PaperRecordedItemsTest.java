package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.nbt.CompoundTag;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PaperRecordedItemsTest {

    @Test
    void testNativeItemHoverRetainsAttributesWithoutMutatingSnapshot() {
        try (var ignored = PaperItemViewTestSupport.install()) {
            var item = ItemStack.of(Material.DIAMOND_SWORD, 1);
            var meta = item.getItemMeta();
            meta.displayName(Component.text("Evidence"));
            item.setItemMeta(meta);
            var saved = PaperItemStackPayloadCodec.encode(item);
            var component = PaperRecordedItems.preview(saved);
            Assertions.assertEquals(HoverEvent.Action.SHOW_ITEM, component.hoverEvent().action());
            Assertions.assertEquals(item, PaperItemStackPayloadCodec.decode(saved));
            Assertions.assertTrue(component.hoverEvent().toString().contains("custom_name"));
        }
    }

    @Test
    void testUnsignedBookUsesDetachedReadOnlyViewAndKeepsFormatting() throws Exception {
        var original = ItemStack.of(Material.WRITABLE_BOOK);
        var meta = (BookMeta) original.getItemMeta();
        meta.pages(Component.text("Original page", NamedTextColor.RED).clickEvent(ClickEvent.runCommand("/danger")));
        original.setItemMeta(meta);
        var payload = new CompoundTag();
        payload.put("previous_book_meta", PaperItemStackPayloadCodec.encode(original));
        var event = PaperEventPresenterTest.event("book_edit", payload);
        var view = PaperRecordedItems.book(event, "previous_book_meta");
        Assertions.assertEquals(Material.WRITTEN_BOOK, view.getType());
        var pages = ((BookMeta) view.getItemMeta()).pages();
        Assertions.assertEquals(NamedTextColor.RED, pages.getFirst().color());
        Assertions.assertNull(pages.getFirst().clickEvent());
        Assertions.assertTrue(pages.getFirst().toString().contains("Original page"));
        Assertions.assertEquals(original, PaperItemStackPayloadCodec.decode(payload.getCompoundOrEmpty("previous_book_meta")));
        Assertions.assertThrows(IllegalArgumentException.class, () -> PaperRecordedItems.book(event, "previous_book_meta.serialized"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> PaperRecordedItems.book(event, "missing"));
    }
}
