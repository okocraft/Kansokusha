package net.okocraft.kansokusha.common.command;

import dev.siroshun.mcmsgdef.DefaultMessageDefiner;
import dev.siroshun.mcmsgdef.MessageKey;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.LinkedHashMap;
import java.util.Map;

@NotNullByDefault
public final class EventFieldMessages {

    private static final DefaultMessageDefiner DEFINER = DefaultMessageDefiner.create();
    private static final Map<String, MessageKey> LABELS = new LinkedHashMap<>();

    static {
        label("action", "Action");
        label("operation", "Operation");
        label("cause", "Cause");
        label("reason", "Reason");
        label("source_event", "Source event");
        label("pre_state", "Before");
        label("post_state", "After");
        label("before", "Before");
        label("after", "After");
        label("old_state", "Before");
        label("new_state", "After");
        label("replaced", "Replaced state");
        label("placed", "Placement properties");
        label("side", "Side");
        label("hand", "Hand");
        label("face", "Face");
        label("source", "Source");
        label("destination", "Destination");
        label("from", "From");
        label("to", "To");
        label("from_world", "Previous world");
        label("source_inventory", "Source inventory");
        label("destination_inventory", "Destination inventory");
        label("transfer_direction", "Direction");
        label("amount_delta", "Container net delta");
        label("item", "Item");
        label("stack", "Item");
        label("source_item", "Source item");
        label("result_item", "Result item");
        label("input_items", "Inputs");
        label("event_result_items", "Event results");
        label("harvest_items", "Harvest items");
        label("shear_drops", "Shear drops");
        label("process_kind", "Process");
        label("fluid", "Fluid");
        label("direction", "Direction");
        label("old_gamemode", "Previous game mode");
        label("new_gamemode", "New game mode");
        label("last_damage_cause", "Last damage cause");
        label("message", "Message");
        label("command", "Command input");
        label("source_kind", "Source kind");
        label("source_name", "Source name");
        label("death_message", "Death message");
        label("spawn_reason", "Spawn reason");
        label("damage_type", "Damage type");
        label("equipment_slot", "Equipment slot");
        label("item_before", "Previous item");
        label("item_after", "New item");
        label("rotation_before", "Previous rotation");
        label("rotation_after", "New rotation");
        label("previous_custom_name", "Previous name");
        label("new_custom_name", "New name");
        label("new_owner", "New owner");
        label("before_size", "Previous size");
        label("after_size", "New size");
        label("profile_name", "Profile name");
        label("before_enabled", "Previously enabled");
        label("after_enabled", "Now enabled");
        label("before_whitelisted", "Previously whitelisted");
        label("after_whitelisted", "Now whitelisted");
        label("Name", "Block");
        label("Properties", "Properties");
        label("position", "Position");
        label("world", "World");
        label("location", "Location");
        label("inventory", "Inventory");
        label("container", "Container");
        label("ingredient", "Ingredient");
        label("fuel", "Fuel");
        label("used_item", "Used item");
        label("transaction_id", "Transaction ID");
        label("required_level", "Required level");
        label("consumed_levels", "Consumed levels");
        label("repair_cost", "Repair cost");
        label("rename_text", "Rename text");
        label("signing", "Signing");
        label("username", "Recorded name");
    }

    private static void label(String field, String english) {
        LABELS.put(field, DEFINER.define("kansokusha.display.field." + field, english));
    }

    public static Component label(String field) {
        var label = LABELS.get(field);
        return label == null ? Component.text(field) : label.asComponent();
    }

    public static Component field(String field, Component value) {
        return EventCommandMessages.DETAIL_LINE.apply(label(field), value);
    }

    public static Map<String, String> defaultMessages() {
        return DEFINER.getCollectedMessages();
    }

    private EventFieldMessages() {
    }
}
