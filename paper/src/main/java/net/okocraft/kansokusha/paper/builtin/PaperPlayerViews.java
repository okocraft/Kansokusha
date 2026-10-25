package net.okocraft.kansokusha.paper.builtin;

import java.util.List;

import static net.okocraft.kansokusha.common.command.EventRenderer.NameKind.OTHER;
import static net.okocraft.kansokusha.paper.builtin.PaperEventView.of;

final class PaperPlayerViews {

    static final List<PaperEventView> VIEWS = List.of(
        of("paper_join", "<actor>backend join<context>", OTHER, "", "$server", "$server"),
        of("paper_quit", "<actor>backend quit<context>", OTHER, "", "$server reason", "$server reason"),
        of("paper_kick", "<actor>kick<context>", OTHER, "", "$server ~reason cause", "$server cause ~reason"),
        of("player_world_change", "<actor>world change<context>", OTHER, "", "from_world $world", "from_world $world"),
        of("player_teleport", "<actor>teleport<context>", OTHER, "", "from $world to cause", "from $world to cause"),
        of("player_gamemode_change", "<actor>game mode change<context>", OTHER, "", "old_gamemode new_gamemode cause", "old_gamemode new_gamemode"),
        of("player_spawn_change", "<actor>respawn point change<context>", OTHER, "", "before after cause", "before after cause"),
        of("player_death", "<actor>death<context>", OTHER, "", "death_message last_damage_cause", "death_message last_damage_cause"),
        of("paper_chat", "<actor>chat<context>", OTHER, "", "message", "message"),
        of("paper_player_command", "<actor>command<context>", OTHER, "", "command", "command"),
        of("paper_server_command", "server command<context>", OTHER, "", "source_kind source_name command", "source_kind source_name command")
    );

    private PaperPlayerViews() {
    }
}
