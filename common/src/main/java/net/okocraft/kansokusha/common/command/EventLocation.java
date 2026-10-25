package net.okocraft.kansokusha.common.command;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.common.search.SearchPage;
import net.okocraft.kansokusha.common.search.query.SearchQuery;
import java.util.Optional;

public record EventLocation(Optional<Key> world, Optional<BlockPosition> position, Optional<Key> server) {
    public static EventLocation of(SearchPage.Event event) {
        return new EventLocation(event.world(), event.position(), event.server());
    }

    public static EventLocation of(SearchQuery.Position point) {
        return new EventLocation(Optional.of(point.world()), Optional.of(new BlockPosition(point.x(), point.y(), point.z())), Optional.empty());
    }

    public Component plain(boolean showWorld) {
        var text = showWorld ? world.map(Key::asString).orElseGet(() -> server.map(Key::asString).orElse("")) : "";
        if (position.isPresent()) {
            var point = position.get();
            text += " (" + point.x() + "," + point.y() + "," + point.z() + ")";
        }
        return text.isEmpty() ? Component.empty() : Component.text(text);
    }
}
