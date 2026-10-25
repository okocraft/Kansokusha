package net.okocraft.kansokusha.common.command;

import net.kyori.adventure.text.Component;
import net.okocraft.kansokusha.common.search.SearchPage;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.List;
import java.util.Optional;

/** A platform interprets only its own known payload generations. */
@ApiStatus.Internal
@NotNullByDefault
@FunctionalInterface
public interface EventPresenter {

    Optional<Content> present(SearchPage.Event event, boolean detailed, EventRenderer renderer);

    record Content(Component summary, List<Component> details) {
        public Content {
            details = List.copyOf(details);
        }
    }
}
