package net.okocraft.kansokusha.paper.builtin;

import java.util.List;

import static net.okocraft.kansokusha.common.command.EventRenderer.NameKind.ENTITY;
import static net.okocraft.kansokusha.paper.builtin.PaperEventView.of;

final class PaperEntityViews {

    static final List<PaperEventView> VIEWS = List.of(
        of("entity_place", "<actor> placed <target><context>", ENTITY, "used_item"),
        of("entity_break", "<actor> removed <target><context>", ENTITY, "cause"),
        of("entity_death", "<target> died; source: <actor><context>", ENTITY, "damage_type"),
        of("entity_spawn", "<target> spawned<context>", ENTITY, "spawn_reason"),
        of("armor_stand_manipulate", "<actor> manipulated <target><context>", ENTITY, "equipment_slot", "player_item_before", "armor_stand_item_before"),
        of("entity_leash_change", "<actor> changed the leash of <target><context>", ENTITY, "action"),
        of("item_frame_change", "<actor> changed <target><context>", ENTITY, "action", "item_before", "item_after", "rotation_after"),
        of("entity_tame", "<actor> tamed <target><context>", ENTITY, "new_owner"),
        of("entity_name_change", "<actor> renamed <target><context>", ENTITY, "previous_custom_name", "new_custom_name")
    );

    private PaperEntityViews() {
    }
}
