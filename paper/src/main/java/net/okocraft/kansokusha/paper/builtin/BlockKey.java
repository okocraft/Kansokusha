package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.position.BlockPosition;
import org.jetbrains.annotations.NotNullByDefault;

@NotNullByDefault
record BlockKey(Key worldKey, BlockPosition position) {
}
