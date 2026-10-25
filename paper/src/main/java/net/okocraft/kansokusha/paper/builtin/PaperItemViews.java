package net.okocraft.kansokusha.paper.builtin;

import java.util.List;

import static net.okocraft.kansokusha.common.command.EventRenderer.NameKind.ITEM;
import static net.okocraft.kansokusha.paper.builtin.PaperEventView.of;

final class PaperItemViews {

    static final List<PaperEventView> VIEWS = List.of(
        of("container_transfer", "<actor>transfer attempt <target><context>", ITEM, "item", "source_inventory destination_inventory item", "item source_inventory destination_inventory"),
        of("container_pickup", "<actor>container pickup <target><context>", ITEM, "source_item", "source_item inventory", "source_item"),
        of("container_process", "<actor>processing <target><context>", ITEM, "", "input_items event_result_items", "process_kind input_items event_result_items"),
        of("container_transaction", "<actor>container delta <target><context>", ITEM, "amount_delta", "transfer_direction amount_delta container", "transfer_direction amount_delta"),
        of("craft_item", "<actor>crafted <target><context>", ITEM, "result_item", "input_items result_item", "result_item"),
        of("anvil_use", "<actor>anvil use <target><context>", ITEM, "result_item", "input_items result_item rename_text", "result_item rename_text repair_cost"),
        of("smith_item", "<actor>smithed <target><context>", ITEM, "result_item", "input_items result_item", "result_item"),
        of("enchant_item", "<actor>enchanted <target><context>", ITEM, "", "item consumed_levels", "item consumed_levels"),
        of("dispenser_dispense", "<actor>dispense <target><context>", ITEM, "item", "item", "item"),
        of("item_drop", "<actor>drop <target><context>", ITEM, "stack", "stack", "stack"),
        of("item_pickup", "<actor>pickup <target><context>", ITEM, "", "stack remaining", "stack remaining"),
        of("book_edit", "<actor>book edit <target><context>", ITEM, "", "previous_book_meta new_book_meta", "signing"),
        of("lectern_change", "<actor>lectern operation <target><context>", ITEM, "", "before after", "action"),
        of("player_trade", "<actor>purchase attempt <target><context>", ITEM, "trade.result", "trade", "trade")
    );

    private PaperItemViews() {
    }
}
