package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.okocraft.kansokusha.common.command.EventRenderer.NameKind;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.jetbrains.annotations.NotNullByDefault;

@NotNullByDefault
final class PaperEventNames {

    static Component name(Key key, NameKind kind) {
        if (!key.namespace().equals("minecraft")) {
            return Component.text(key.asString());
        }
        String translation = null;
        if (kind == NameKind.ENTITY) {
            var type = Registry.ENTITY_TYPE.get(NamespacedKey.fromString(key.asString()));
            if (type != null) {
                translation = type.translationKey();
            }
        } else if (kind == NameKind.BLOCK || kind == NameKind.ITEM) {
            var material = Material.matchMaterial(key.asString());
            if (material != null) {
                if (kind == NameKind.BLOCK && material.asBlockType() != null) {
                    translation = material.asBlockType().translationKey();
                } else if (kind == NameKind.ITEM && material.asItemType() != null) {
                    translation = material.asItemType().translationKey();
                }
            }
        }
        return translation == null ? Component.text(key.asString())
            : Component.translatable(translation, key.asString())
                .hoverEvent(HoverEvent.showText(Component.text(key.asString())));
    }

    private PaperEventNames() {
    }
}
