package net.okocraft.kansokusha.paper.builtin;

import java.util.List;

import static net.okocraft.kansokusha.common.command.EventRenderer.NameKind.ITEM;
import static net.okocraft.kansokusha.paper.builtin.PaperEventView.of;

final class PaperItemViews {

    static final List<PaperEventView> VIEWS = List.of(
        of("container_transfer", "<actor> attempted to transfer <target><context>", ITEM, "item", "source_inventory", "destination_inventory"),
        of("container_pickup", "<actor> picked up world item <target><context>", ITEM, "source_item"),
        of("container_process", "<actor> processed <target><context>", ITEM, "process_kind", "input_items", "event_result_items"),
        of("container_transaction", "<actor> changed <target> in a container<context>", ITEM, "transfer_direction", "amount_delta"),
        of("craft_item", "<actor> crafted <target><context>", ITEM, "result_item"),
        of("anvil_use", "<actor> used an anvil to produce <target><context>", ITEM, "result_item", "rename_text", "repair_cost"),
        of("smith_item", "<actor> smithed <target><context>", ITEM, "result_item"),
        of("enchant_item", "<actor> enchanted <target><context>", ITEM, "item", "consumed_levels"),
        of("dispenser_dispense", "<actor> dispensed <target><context>", ITEM, "item"),
        of("item_drop", "<actor> dropped <target> (player → world)<context>", ITEM, "stack"),
        of("item_pickup", "<actor> picked up <target> (world → player)<context>", ITEM, "stack", "remaining"),
        of("book_edit", "<actor> edited <target><context>", ITEM, "signing"),
        of("lectern_change", "<actor> changed the lectern book: <target><context>", ITEM, "action"),
        of("player_trade", "<actor> attempted to purchase <target><context>", ITEM, "trade")
    );

    private PaperItemViews() {
    }
}
