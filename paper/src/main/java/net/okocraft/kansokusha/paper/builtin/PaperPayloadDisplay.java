package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.okocraft.kansokusha.common.command.EventCommandMessages;
import net.okocraft.kansokusha.common.command.EventFieldMessages;
import net.okocraft.kansokusha.common.command.EventPresentationMessages;
import net.okocraft.kansokusha.common.command.EventRenderer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/** Human-readable values are distinct from the explicit archived-data expansion. */
@NotNullByDefault
final class PaperPayloadDisplay {

    static @Nullable Tag at(CompoundTag root, String path) {
        Tag value = root;
        for (var part : path.split("\\.")) {
            if (value instanceof CompoundTag compound) {
                value = compound.get(part);
            } else if (value instanceof ListTag list && part.matches("[0-9]{1,6}")) {
                var index = Integer.parseInt(part);
                value = index < list.size() ? list.get(index) : null;
            } else {
                return null;
            }
            if (value == null) {
                return null;
            }
        }
        return value;
    }

    static Component preview(Tag tag, String field) {
        return preview(tag, field, true);
    }

    static Component preview(Tag tag, String field, boolean debug) {
        if (tag instanceof CompoundTag compound) {
            if (compound.get("serialized") instanceof ByteArrayTag bytes) {
                return PaperRecordedItems.preview(compound);
            }
            var state = compound.getStringOr("id", compound.getStringOr("Name", ""));
            if (!state.isEmpty()) {
                return PaperEventNames.name(Key.key(state), EventRenderer.NameKind.BLOCK)
                    .hoverEvent(HoverEvent.showText(stateHover(compound, debug)));
            }
            if (compound.contains("holder_kind")) {
                var block = compound.getStringOr("holder_block_type", "");
                var name = block.isEmpty() ? Component.text(compound.getStringOr("type", ""))
                    : PaperEventNames.name(Key.key(block), EventRenderer.NameKind.BLOCK);
                var location = compound.get("location");
                return location == null ? name : name.append(Component.text(" @ ")).append(preview(location, "location", debug));
            }
            if (compound.contains("result") && compound.contains("ingredients")) {
                return preview(compound.get("ingredients"), "ingredients", debug).append(Component.text(" → "))
                    .append(preview(compound.get("result"), "result", debug));
            }
            if (compound.contains("uuid")) {
                var type = compound.getStringOr("type", "");
                var name = type.isEmpty() ? EventFieldMessages.label(field)
                    : PaperEventNames.name(Key.key(type), EventRenderer.NameKind.ENTITY);
                return debug ? name.hoverEvent(HoverEvent.showText(Component.text(compound.getStringOr("uuid", "")))) : name;
            }
            if (compound.contains("component")) {
                return Component.text(EventRenderer.preview(text(compound, field)));
            }
            if (compound.contains("location")) {
                return preview(compound.get("location"), "location", debug);
            }
            if (compound.contains("x") && compound.contains("z")) {
                return Component.text(compound.getStringOr("world", "") + " (" + compound.getDoubleOr("x", 0)
                    + "," + (compound.contains("y") ? compound.getDoubleOr("y", 0) + "," : "") + compound.getDoubleOr("z", 0) + ")");
            }
            return compound.isEmpty() ? EventPresentationMessages.EMPTY.asComponent() : EventPresentationMessages.EXTRA.asComponent();
        }
        if (tag instanceof ListTag list) {
            if (isText(tag)) {
                return Component.text(EventRenderer.preview(text(tag, field)));
            }
            var result = Component.empty();
            for (var i = 0; i < Math.min(3, list.size()); i++) {
                result = result.append(i == 0 ? Component.empty() : Component.text(", ")).append(preview(list.get(i), field, debug));
            }
            return list.size() > 3 ? result.append(Component.text(" … (" + list.size() + ")")) : result;
        }
        if (tag instanceof StringTag) {
            return EventFieldMessages.value(field, EventRenderer.preview(text(tag, field)));
        }
        return Component.text(tag instanceof ByteTag ? Boolean.toString(tag.asByte().orElseThrow() != 0) : tag.toString());
    }

    static boolean isText(Tag tag) {
        return tag instanceof StringTag || tag instanceof CompoundTag compound && compound.contains("component")
            || tag instanceof ListTag list && !list.isEmpty() && list.stream().allMatch(value ->
                value instanceof StringTag || value instanceof CompoundTag compound && (compound.isEmpty() || compound.contains("component")));
    }

    static String text(Tag tag, String field) {
        if (tag instanceof ListTag list) {
            return String.join("\n", list.stream().map(value -> text(value, field)).toList());
        }
        if (tag instanceof CompoundTag compound) {
            return compound.contains("component") ? componentText(compound.getStringOr("component", "")) : "";
        }
        var value = ((StringTag) tag).value();
        return field.startsWith("~") || field.equals("death_message") || field.equals("component") ? componentText(value) : value;
    }

    static Component hoverValue(Tag tag, String field) {
        if (tag instanceof CompoundTag compound && (compound.contains("id") || compound.contains("Name"))) {
            return preview(tag, field).hoverEvent(null).append(Component.newline()).append(stateHover(compound));
        }
        return preview(tag, field);
    }

    static Component stateHover(CompoundTag compound) {
        return stateHover(compound, true);
    }

    static Component stateHover(CompoundTag compound, boolean debug) {
        var properties = compound.getCompoundOrEmpty(compound.contains("properties") ? "properties" : "Properties");
        var lines = new ArrayList<Component>();
        var type = compound.getStringOr("id", compound.getStringOr("Name", ""));
        lines.add(debug || type.isEmpty() ? Component.text(type) : PaperEventNames.name(Key.key(type), EventRenderer.NameKind.BLOCK));
        for (var key : properties.keySet().stream().sorted().limit(6).toList()) {
            lines.add(EventFieldMessages.field(key, preview(properties.get(key), key)));
        }
        return EventRenderer.join(lines);
    }

    static List<Component> details(CompoundTag payload) {
        var lines = new ArrayList<Component>();
        appendDetails(payload, Component.empty(), lines);
        return List.copyOf(lines);
    }

    private static void appendDetails(Tag tag, Component path, List<Component> lines) {
        if (tag instanceof CompoundTag compound && compound.get("serialized") instanceof ByteArrayTag bytes) {
            appendDetails(itemData(bytes), path, lines);
        } else if (tag instanceof CompoundTag compound && !compound.isEmpty()) {
            for (var key : compound.keySet().stream().sorted().toList()) {
                appendDetails(compound.get(key), path.append(Component.text(" / ")).append(EventFieldMessages.label(key)), lines);
            }
        } else if (tag instanceof ListTag list && !list.isEmpty()) {
            for (var i = 0; i < list.size(); i++) {
                appendDetails(list.get(i), path.append(Component.text(" [" + i + "]")), lines);
            }
        } else {
            var value = tag instanceof StringTag string ? string.value() : tag.toString();
            for (var line : EventRenderer.textLines(value)) {
                lines.add(EventCommandMessages.DETAIL_LINE.apply(path, line));
            }
        }
    }

    private static CompoundTag itemData(ByteArrayTag bytes) {
        try {
            return NbtIo.readCompressed(new ByteArrayInputStream(bytes.getAsByteArray()), NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String componentText(String json) {
        return PlainTextComponentSerializer.plainText().serialize(PaperComponentPayloadCodec.decode(json));
    }

    private PaperPayloadDisplay() {
    }
}
