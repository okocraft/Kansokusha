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
        of("bucket_fill", "<actor> bucket fill operation: <target><context>", ITEM, "pre_state"),
        of("block_harvest", "<actor> harvest/shear operation on <target><context>", BLOCK, "operation", "harvest_items", "shear_drops"),
        of("flower_pot_change", "<actor> flower pot operation: <target><context>", ITEM, "action"),
        of("block_ignite", "<actor> ignition of <target><context>", BLOCK, "cause"),
        of("block_burn", "Fire destruction of <target>; source: <actor><context>", BLOCK),
        of("tnt_prime", "<actor> priming of <target><context>", BLOCK, "cause"),
        of("explosion_block_change", "<actor> explosion block change: <target><context>", BLOCK),
        of("piston_move", "<actor> piston movement of <target><context>", BLOCK, "action", "direction"),
        of("entity_block_change", "<actor> block change: <target><context>", BLOCK, "before", "to"),
        of("natural_block_change", "Natural change of <target><context>", BLOCK, "source_event", "pre_state", "post_state"),
        of("fluid_change", "<actor> flow into <target><context>", BLOCK, "fluid"),
        of("sponge_absorb", "<actor> absorption of <target><context>", BLOCK),
        of("block_fertilize", "<actor> bone meal operation on <target><context>", BLOCK, "post_state"),
        of("cauldron_level_change", "<actor> block change: <target><context>", BLOCK, "old_state", "new_state", "reason"),
        of("block_interaction", "<actor> interacted with <target><context>", BLOCK, "operation")
    );

    private PaperBlockViews() {
    }
}
