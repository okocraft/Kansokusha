package net.okocraft.kansokusha.paper.builtin;

import java.util.List;

import static net.okocraft.kansokusha.common.command.EventRenderer.NameKind.BLOCK;
import static net.okocraft.kansokusha.common.command.EventRenderer.NameKind.ITEM;
import static net.okocraft.kansokusha.paper.builtin.PaperEventView.of;

final class PaperBlockViews {

    static final List<PaperEventView> VIEWS = List.of(
        of("block_break", "<actor>break attempt <target><context>", BLOCK, "", "", ""),
        of("block_place", "<actor>place attempt <target><context>", BLOCK, "", "replaced", ""),
        of("sign_change", "<actor>sign edit <target><context>", BLOCK, "", "before after", "side after"),
        of("bucket_empty", "<actor>bucket empty <target><context>", ITEM, "", "pre_state result_item", "pre_state"),
        of("bucket_fill", "<actor>bucket fill <target><context>", ITEM, "", "pre_state result_item", "pre_state"),
        of("block_harvest", "<actor>harvest/shear <target><context>", BLOCK, "", "harvest_items shear_drops", "operation harvest_items shear_drops"),
        of("flower_pot_change", "<actor>flower pot operation <target><context>", ITEM, "", "before after", "action"),
        of("block_ignite", "<actor>ignition <target><context>", BLOCK, "", "cause", "cause"),
        of("block_burn", "<actor>burning <target><context>", BLOCK, "", "", ""),
        of("tnt_prime", "<actor>TNT priming <target><context>", BLOCK, "", "cause", "cause"),
        of("explosion_block_change", "<actor>explosion change <target><context>", BLOCK, "", "", ""),
        of("piston_move", "<actor>piston movement <target><context>", BLOCK, "", "from to", "action direction"),
        of("entity_block_change", "<actor>block change <target><context>", BLOCK, "", "before to", "before to"),
        of("natural_block_change", "natural change <target><context>", BLOCK, "", "pre_state post_state", "source_event pre_state post_state"),
        of("fluid_change", "<actor>fluid flow <target><context>", BLOCK, "", "source destination", "fluid"),
        of("sponge_absorb", "<actor>absorption <target><context>", BLOCK, "", "", ""),
        of("block_fertilize", "<actor>bone meal <target><context>", BLOCK, "", "pre_state post_state", "post_state"),
        of("cauldron_level_change", "<actor>cauldron change <target><context>", BLOCK, "", "old_state new_state reason", "old_state new_state reason"),
        of("block_interaction", "<actor>interaction <target><context>", BLOCK, "", "", "operation")
    );

    private PaperBlockViews() {
    }
}
