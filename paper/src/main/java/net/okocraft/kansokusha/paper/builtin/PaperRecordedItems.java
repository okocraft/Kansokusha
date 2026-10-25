package net.okocraft.kansokusha.paper.builtin;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.okocraft.kansokusha.common.command.EventFieldMessages;
import net.okocraft.kansokusha.common.command.EventRenderer;
import net.okocraft.kansokusha.common.search.SearchPage;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Detached item previews only; no archived item is added to a live inventory. */
@ApiStatus.Internal
@NotNullByDefault
public final class PaperRecordedItems {

    static Component preview(CompoundTag tag) {
        var item = PaperItemStackPayloadCodec.decode(tag);
        if (item.isEmpty()) {
            return PaperViewMessages.EMPTY_ITEM.asComponent();
        }
        var hover = item.clone();
        hover.setAmount(1);
        if (hover.getItemMeta() instanceof BookMeta meta) {
            // Tooltip metadata remains useful without transmitting entire book pages on every row.
            meta.pages(Collections.nCopies(meta.getPageCount(), Component.empty()));
            hover.setItemMeta(meta);
        }
        return PaperEventNames.name(item.getType().key(), EventRenderer.NameKind.ITEM)
            .append(Component.text(" ×" + item.getAmount())).hoverEvent(hover.asHoverEvent());
    }

    static Component readAction(UUID eventId, String path, Tag tag) {
        if (!(tag instanceof CompoundTag compound) || !compound.contains("serialized")) {
            return Component.empty();
        }
        var item = PaperItemStackPayloadCodec.decode(compound);
        return item.getItemMeta() instanceof BookMeta ? Component.space().append(PaperViewMessages.READ.asComponent()
            .clickEvent(ClickEvent.runCommand("/kansokusha event " + eventId + " read " + path))) : Component.empty();
    }

    static List<Component> items(CompoundTag payload, UUID eventId) {
        var lines = new ArrayList<Component>();
        visit(payload, "", (path, item) -> lines.add(EventFieldMessages.field(path, preview(item))
            .append(readAction(eventId, path, item))));
        return List.copyOf(lines);
    }

    static boolean hasItems(CompoundTag payload) {
        var found = new boolean[]{false};
        visit(payload, "", (path, item) -> found[0] = true);
        return found[0];
    }

    private static void visit(Tag value, String path, java.util.function.BiConsumer<String, CompoundTag> action) {
        if (value instanceof CompoundTag compound) {
            if (compound.contains("serialized")) {
                action.accept(path, compound);
                return;
            }
            for (var key : compound.keySet().stream().sorted().toList()) {
                visit(compound.get(key), path.isEmpty() ? key : path + "." + key, action);
            }
        } else if (value instanceof ListTag list) {
            for (var i = 0; i < list.size(); i++) {
                visit(list.get(i), path + "." + i, action);
            }
        }
    }

    public static ItemStack book(SearchPage.Event event, String path) throws IOException {
        if (!PaperEventPresenter.supports(event) || event.payload().isEmpty()
            || event.payload().get().generation().value() != 1 || !path.matches("[a-zA-Z0-9_.]{1,128}")) {
            throw new IllegalArgumentException("Unsupported item view");
        }
        var tag = PaperPayloadDisplay.at(PaperPayloadNbtCodec.decode(event.payload().get().bytes()), path);
        if (!(tag instanceof CompoundTag compound) || !compound.contains("serialized")) {
            throw new IllegalArgumentException("Item snapshot not found");
        }
        var original = PaperItemStackPayloadCodec.decode(compound);
        if (!(original.getItemMeta() instanceof BookMeta source)) {
            throw new IllegalArgumentException("Not a book snapshot");
        }
        var view = ItemStack.of(Material.WRITTEN_BOOK);
        var meta = (BookMeta) view.getItemMeta();
        meta.setTitle(source.hasTitle() ? source.getTitle() : "Kansokusha");
        meta.setAuthor(source.hasAuthor() ? source.getAuthor() : "Kansokusha");
        meta.pages(source.pages().stream().map(PaperRecordedItems::inert).toList());
        view.setItemMeta(meta);
        return view;
    }

    private static Component inert(Component component) {
        var serializer = GsonComponentSerializer.gson();
        var tree = JsonParser.parseString(serializer.serialize(component));
        stripActions(tree);
        return serializer.deserialize(tree.toString());
    }

    private static void stripActions(JsonElement value) {
        if (value.isJsonObject()) {
            var object = value.getAsJsonObject();
            for (var key : List.of("clickEvent", "hoverEvent", "click_event", "hover_event", "insertion")) {
                object.remove(key);
            }
            object.entrySet().forEach(entry -> stripActions(entry.getValue()));
        } else if (value.isJsonArray()) {
            value.getAsJsonArray().forEach(PaperRecordedItems::stripActions);
        }
    }

    private PaperRecordedItems() {
    }
}
