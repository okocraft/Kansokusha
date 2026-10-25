package net.okocraft.kansokusha.paper.builtin;

import java.util.List;

import static net.okocraft.kansokusha.common.command.EventRenderer.NameKind.OTHER;
import static net.okocraft.kansokusha.paper.builtin.PaperEventView.of;

final class PaperAdministrativeViews {

    static final List<PaperEventView> VIEWS = List.of(
        of("gamerule_change", "game rule change <target><context>", OTHER, "", "before after", "before after"),
        of("world_difficulty_change", "difficulty change<context>", OTHER, "", "before after", "before after"),
        of("world_border_change", "border request<context>", OTHER, "", "before after before_size after_size transition_duration_ticks", "action before after before_size after_size"),
        of("world_spawn_change", "world spawn change<context>", OTHER, "", "before after", "before after"),
        of("whitelist_change", "whitelist change<context>", OTHER, "", "action profile_name before_enabled after_enabled before_whitelisted after_whitelisted", "action profile_name before_enabled after_enabled before_whitelisted after_whitelisted")
    );

    private PaperAdministrativeViews() {
    }
}
