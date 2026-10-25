package net.okocraft.kansokusha.paper.builtin;

import java.util.List;

import static net.okocraft.kansokusha.common.command.EventRenderer.NameKind.OTHER;
import static net.okocraft.kansokusha.paper.builtin.PaperEventView.of;

final class PaperAdministrativeViews {

    static final List<PaperEventView> VIEWS = List.of(
        of("gamerule_change", "Game rule <target> changed<context>", OTHER, "before", "after"),
        of("world_difficulty_change", "World difficulty changed<context>", OTHER, "before", "after"),
        of("world_border_change", "World border change requested<context>", OTHER, "action", "before", "after", "before_size", "after_size"),
        of("world_spawn_change", "World spawn changed<context>", OTHER, "before", "after"),
        of("whitelist_change", "Whitelist changed<context>", OTHER, "action", "profile_name", "before_enabled", "after_enabled", "before_whitelisted", "after_whitelisted")
    );

    private PaperAdministrativeViews() {
    }
}
