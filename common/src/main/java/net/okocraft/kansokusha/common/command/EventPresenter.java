package net.okocraft.kansokusha.common.command;

import net.kyori.adventure.text.Component;
import net.okocraft.kansokusha.common.search.SearchPage;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.Optional;

/** A platform interprets only its own known payload generations. */
@ApiStatus.Internal
@NotNullByDefault
@FunctionalInterface
public interface EventPresenter {

    Optional<Content> present(SearchPage.Event event, boolean detailed, EventRenderer renderer);

    record Content(Component summary, List<Component> details, Component hover, Map<String, Section> sections) {
        public Content {
            details = List.copyOf(details);
            sections = Map.copyOf(sections);
        }

        public Content(Component summary, List<Component> details) {
            this(summary, details, Component.empty(), Map.of());
        }
    }

    /** Deferred expansion: normal event views do not build the full archived data tree. */
    record Section(Component label, Supplier<List<Component>> lines) {
    }
}
