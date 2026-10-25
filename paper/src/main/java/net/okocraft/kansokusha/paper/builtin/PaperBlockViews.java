package net.okocraft.kansokusha.paper.builtin;

import java.util.List;

import static net.okocraft.kansokusha.common.command.EventRenderer.NameKind.BLOCK;
import static net.okocraft.kansokusha.common.command.EventRenderer.NameKind.ITEM;
import static net.okocraft.kansokusha.paper.builtin.PaperEventView.of;

final class PaperBlockViews {

    static final List<PaperEventView> VIEWS = List.of(
        of("block_break", "<actor> attempted to break <target><context>", BLOCK),
        of("block_place", "<actor> attempted to place <target><context>", BLOCK),
        of("sign_change", "<actor> edited <target><context>", BLOCK, "side", "after"),
        of("bucket_empty", "<actor> used <target> to empty a bucket<context>", ITEM, "pre_state"),
        of("bucket_fill", "<actor> filled a bucket: <target><context>", ITEM, "pre_state"),
        of("block_harvest", "<actor> harvested/sheared <target><context>", BLOCK, "operation", "harvest_items", "shear_drops"),
        of("flower_pot_change", "<actor> changed flower pot contents: <target><context>", ITEM, "action"),
        of("block_ignite", "<actor> ignited <target><context>", BLOCK, "cause"),
        of("block_burn", "<target> burned; source: <actor><context>", BLOCK),
        of("tnt_prime", "<actor> primed <target><context>", BLOCK, "cause"),
        of("explosion_block_change", "<actor> explosion affected <target><context>", BLOCK),
        of("piston_move", "<actor> moved <target><context>", BLOCK, "action", "direction"),
        of("entity_block_change", "<actor> changed <target><context>", BLOCK, "before", "to"),
        of("natural_block_change", "<target> changed naturally<context>", BLOCK, "source_event", "pre_state", "post_state"),
        of("fluid_change", "<actor> flowed into <target><context>", BLOCK, "fluid"),
        of("sponge_absorb", "<actor> absorbed <target><context>", BLOCK),
        of("block_fertilize", "<actor> applied bone meal to <target><context>", BLOCK, "post_state"),
        of("cauldron_level_change", "<actor> changed <target><context>", BLOCK, "old_state", "new_state", "reason"),
        of("block_interaction", "<actor> interacted with <target><context>", BLOCK, "operation")
    );

    private PaperBlockViews() {
    }
}
