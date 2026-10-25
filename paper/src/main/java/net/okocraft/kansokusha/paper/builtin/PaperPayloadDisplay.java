package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.okocraft.kansokusha.common.command.EventCommandMessages;
import net.okocraft.kansokusha.common.command.EventFieldMessages;
import net.okocraft.kansokusha.common.command.EventRenderer;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;

/** A read-only view of decoded data. Stored components are never executed as chat UI. */
@NotNullByDefault
final class PaperPayloadDisplay {

    static Component preview(Tag tag, String field) {
        if (tag instanceof CompoundTag compound) {
            if (compound.contains("Name")) {
                return PaperEventNames.name(Key.key(compound.getStringOr("Name", "minecraft:air")),
                    EventRenderer.NameKind.BLOCK).append(Component.text(
                        compound.getCompoundOrEmpty("Properties").isEmpty() ? ""
                            : " " + EventRenderer.preview(compound.getCompoundOrEmpty("Properties").toString())));
            }
            if (compound.contains("component")) {
                return Component.text(EventRenderer.preview(componentText(compound.getStringOr("component", ""))));
            }
            if (compound.contains("x") && compound.contains("z")) {
                return Component.text(compound.getStringOr("world", "") + " "
                    + compound.get("x") + " " + (compound.contains("y") ? compound.get("y") + " " : "") + compound.get("z"));
            }
        }
        if (tag instanceof ListTag list) {
            var result = Component.empty();
            for (var i = 0; i < Math.min(3, list.size()); i++) {
                if (i > 0) {
                    result = result.append(Component.text(", "));
                }
                result = result.append(preview(list.get(i), field));
            }
            return list.size() > 3 ? result.append(Component.text(" … (" + list.size() + ")")) : result;
        }
        if (tag instanceof StringTag string) {
            var value = string.value();
            if (field.equals("component") || field.equals("death_message")) {
                value = componentText(value);
            }
            return Component.text(EventRenderer.preview(value));
        }
        return Component.text(EventRenderer.preview(tag.toString()));
    }

    static List<Component> details(CompoundTag payload) {
        var lines = new ArrayList<Component>();
        appendDetails(payload, Component.empty(), lines);
        return List.copyOf(lines);
    }

    private static void appendDetails(Tag tag, Component path, List<Component> lines) {
        if (tag instanceof CompoundTag compound && !compound.isEmpty()) {
            // Stable ordering makes before/after records and comparisons predictable.
            for (var key : compound.keySet().stream().sorted().toList()) {
                appendDetails(compound.get(key), path.append(Component.text(" / "))
                    .append(EventFieldMessages.label(key)), lines);
            }
        } else if (tag instanceof ListTag list && !list.isEmpty()) {
            for (var i = 0; i < list.size(); i++) {
                appendDetails(list.get(i), path.append(Component.text(" [" + i + "]")), lines);
            }
        } else {
            var value = tag instanceof StringTag string ? string.value() : tag.toString();
            // Chunk long values without discarding anything or splitting surrogate pairs.
            if (value.isEmpty()) {
                lines.add(EventCommandMessages.DETAIL_LINE.apply(path, Component.empty()));
            }
            while (!value.isEmpty()) {
                var end = value.offsetByCodePoints(0, Math.min(256, value.codePointCount(0, value.length())));
                lines.add(EventCommandMessages.DETAIL_LINE.apply(path, Component.text(value.substring(0, end))));
                value = value.substring(end);
            }
        }
    }

    private static String componentText(String json) {
        return PlainTextComponentSerializer.plainText().serialize(PaperComponentPayloadCodec.decode(json));
    }

    private PaperPayloadDisplay() {
    }
}
