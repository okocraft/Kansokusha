package net.okocraft.kansokusha.common.command;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.okocraft.kansokusha.common.search.query.SearchQuery;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.LinkedHashSet;
import java.util.Optional;

/** Layout follows query constraints, so it stays stable across pagination. */
@ApiStatus.Internal
@NotNullByDefault
public record SearchDisplayScope(Optional<SearchQuery.Position> position, Optional<Key> world) {

    public static SearchDisplayScope of(SearchQuery query) {
        var conditions = query.conditions();
        var position = conditions.positions().size() == 1
            ? Optional.of(conditions.positions().iterator().next()) : Optional.<SearchQuery.Position>empty();
        var worlds = new LinkedHashSet<Key>();
        if (!conditions.positions().isEmpty()) {
            conditions.positions().forEach(value -> worlds.add(value.world()));
        } else if (!conditions.worlds().isEmpty()) {
            worlds.addAll(conditions.worlds());
        } else {
            conditions.around().forEach(value -> worlds.add(value.world()));
        }
        return new SearchDisplayScope(position, worlds.size() == 1
            ? Optional.of(worlds.iterator().next()) : Optional.empty());
    }

    public Optional<Component> header(EventRenderer renderer) {
        if (this.position.isPresent()) return Optional.of(renderer.location(EventLocation.of(this.position.get()), true));
        return this.world.map(key -> renderer.location(new EventLocation(Optional.of(key), Optional.empty(), Optional.empty()), true));
    }

    public Optional<Component> header() {
        if (this.position.isPresent()) {
            var point = this.position.get();
            return Optional.of(Component.text(point.world().asString() + " (" + point.x() + "," + point.y() + "," + point.z() + ")"));
        }
        return this.world.map(key -> Component.text(key.asString()));
    }
}
