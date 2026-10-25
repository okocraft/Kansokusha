package net.okocraft.kansokusha.paper.builtin;

import java.util.List;

import static net.okocraft.kansokusha.common.command.EventRenderer.NameKind.OTHER;
import static net.okocraft.kansokusha.paper.builtin.PaperEventView.of;

final class PaperPlayerViews {

    static final List<PaperEventView> VIEWS = List.of(
        of("paper_join", "<actor> joined the backend<context>", OTHER, "$server"),
        of("paper_quit", "<actor> left the backend<context>", OTHER, "$server", "reason"),
        of("paper_kick", "<actor> received a kick decision<context>", OTHER, "$server", "cause", "~reason"),
        of("player_world_change", "<actor> changed world<context>", OTHER, "from_world", "$world"),
        of("player_teleport", "<actor> teleport operation<context>", OTHER, "from", "$world", "to", "cause"),
        of("player_gamemode_change", "<actor> changed game mode<context>", OTHER, "old_gamemode", "new_gamemode"),
        of("player_spawn_change", "<actor> changed respawn point<context>", OTHER, "before", "after", "cause"),
        of("player_death", "<actor> died<context>", OTHER, "death_message", "last_damage_cause"),
        of("paper_chat", "<actor> chat input<context>", OTHER, "message"),
        of("paper_player_command", "<actor> entered a command<context>", OTHER, "command"),
        of("paper_server_command", "Command input<context>", OTHER, "source_kind", "source_name", "command")
    );

    private PaperPlayerViews() {
    }
}
