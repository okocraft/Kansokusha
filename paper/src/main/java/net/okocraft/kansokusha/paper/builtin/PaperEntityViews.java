package net.okocraft.kansokusha.paper.builtin;

import java.util.List;

import static net.okocraft.kansokusha.common.command.EventRenderer.NameKind.ENTITY;
import static net.okocraft.kansokusha.paper.builtin.PaperEventView.of;

final class PaperEntityViews {

    static final List<PaperEventView> VIEWS = List.of(
        of("entity_place", "<actor>entity placement <target><context>", ENTITY, "", "used_item", "used_item"),
        of("entity_break", "<actor>entity removal <target><context>", ENTITY, "", "cause", "cause"),
        of("entity_death", "<actor>entity death <target><context>", ENTITY, "", "damage_type", "damage_type"),
        of("entity_spawn", "entity spawn <target><context>", ENTITY, "", "spawn_reason", "spawn_reason"),
        of("armor_stand_manipulate", "<actor>equipment operation <target><context>", ENTITY, "", "equipment_slot player_item_before armor_stand_item_before", "equipment_slot player_item_before armor_stand_item_before"),
        of("entity_leash_change", "<actor>leash operation <target><context>", ENTITY, "", "action reason", "action"),
        of("item_frame_change", "<actor>frame operation <target><context>", ENTITY, "", "item_before item_after rotation_before rotation_after", "action item_before item_after rotation_after"),
        of("entity_tame", "<actor>taming <target><context>", ENTITY, "", "new_owner", "new_owner"),
        of("entity_name_change", "<actor>rename <target><context>", ENTITY, "", "previous_custom_name new_custom_name", "previous_custom_name new_custom_name")
    );

    private PaperEntityViews() {
    }
}
