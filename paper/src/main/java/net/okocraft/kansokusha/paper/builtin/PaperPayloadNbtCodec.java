package net.okocraft.kansokusha.paper.builtin;

import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.position.BlockPosition;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Compact binary codec for Paper built-in payloads.
 *
 * <p>The event type and payload generation define the payload schema, so the persisted bytes do
 * not need NBT's repeated field-name strings. Built-in field names and common fixed strings are
 * encoded as small integer IDs; unknown names still have a literal fallback for block-state
 * properties and other open-ended nested data.</p>
 *
 * <p>The in-memory {@link CompoundTag} representation is intentionally retained. It keeps each
 * event codec readable and lets tests inspect decoded payloads without making the on-disk format
 * self-describing.</p>
 */
@ApiStatus.Internal
@NotNullByDefault
public final class PaperPayloadNbtCodec {

    private static final int TYPE_BYTE = 1;
    private static final int TYPE_SHORT = 2;
    private static final int TYPE_INT = 3;
    private static final int TYPE_LONG = 4;
    private static final int TYPE_FLOAT = 5;
    private static final int TYPE_DOUBLE = 6;
    private static final int TYPE_BYTE_ARRAY = 7;
    private static final int TYPE_STRING = 8;
    private static final int TYPE_LIST = 9;
    private static final int TYPE_COMPOUND = 10;
    private static final int TYPE_INT_ARRAY = 11;
    private static final int TYPE_LONG_ARRAY = 12;
    private static final int TYPE_FALSE = 13;
    private static final int TYPE_TRUE = 14;
    private static final int TYPE_UUID_STRING = 15;
    private static final int TYPE_MINECRAFT_KEY_STRING = 16;
    private static final int TYPE_KNOWN_STRING = 17;

    // IDs are part of payload generation 1. Append new names; do not reorder existing entries.
    private static final String[] FIELD_NAMES = {
        "Name", "Properties",
        "x", "y", "z", "world", "position", "location",
        "pre_state", "post_state", "before", "after", "old_state", "new_state",
        "replaced", "placed", "state", "source_state", "source_block_state",
        "source", "destination", "from", "to", "origin", "sponge_origin",
        "piston_origin", "priming_block", "source_block", "attached_block",
        "cause", "reason", "source_event", "source_kind", "action", "operation",
        "semantics", "type", "kind", "actor_kind", "holder_kind",
        "actor_entity_uuid", "actor_entity_type", "source_entity_uuid", "source_entity_type",
        "shooter_entity_uuid", "shooter_entity_type", "owner_uuid",
        "shooter_block_world", "priming_block_world", "source_block_world", "piston_world",
        "holder_uuid", "holder_type", "holder_block_type", "holder_class",
        "uuid", "profile_uuid", "killer_uuid", "killer_kind", "killer_type",
        "item_entity_uuid", "sender_uuid", "executor_uuid",
        "sender_kind", "sender_name", "sender_entity_type",
        "executor_kind", "executor_entity_type",
        "item", "stack", "serialized", "source_item", "result_item", "used_item",
        "item_before", "item_after", "player_item_before", "armor_stand_item_before",
        "ingredient", "fuel", "result", "ingredients", "adjusted_ingredient_1",
        "input_items", "event_result_items", "harvest_items", "shear_tool", "shear_drops",
        "previous_book_meta", "new_book_meta", "trade", "merchant",
        "source_inventory", "destination_inventory", "initiator_inventory", "inventory", "container",
        "initiator_role", "transfer_direction", "process_kind", "processing_block_type",
        "size", "remaining", "slot", "uses", "max_uses", "demand", "special_price",
        "villager_experience", "dropped_exp", "new_exp", "new_total_exp", "new_level",
        "clicked_x", "clicked_y", "clicked_z", "transition_duration_ticks",
        "before_size", "after_size", "price_multiplier",
        "message", "command", "source_name", "death_message", "last_damage_cause",
        "game_rule", "profile_name", "component", "previous_custom_name", "new_custom_name",
        "from_world", "old_gamemode", "new_gamemode", "relative_flags",
        "yaw", "pitch", "direction", "face", "hand", "side", "equipment_slot",
        "rotation_before", "rotation_after", "bucket", "fluid", "scope", "transition_type",
        "present", "forced", "keep_inventory", "keep_level", "signing", "persistent",
        "fixed", "drop_leash", "indirect_damage", "experience_reward", "ignore_discounts",
        "rewarding_experience", "increasing_trade_uses", "source_present",
        "before_enabled", "after_enabled", "before_whitelisted", "after_whitelisted",
        "new_owner", "hanging"
    };

    // Common schema constants. IDs are append-only for the same reason as FIELD_NAMES.
    private static final String[] KNOWN_STRINGS = {
        "block", "entity", "player", "none", "other", "world", "standalone",
        "source", "destination", "push", "pull", "unknown", "world_item",
        "gameplay_world_state_transition", "successful_teleport_operation",
        "established_gamemode_state_transition",
        "non_cancelled_automated_transfer_attempt",
        "non_cancelled_container_pickup_attempt",
        "non_cancelled_container_process_event",
        "global_toggle", "profile_add", "profile_remove",
        "world_gamerule_change", "world_difficulty_change",
        "world_border_center_change", "world_border_bounds_change",
        "spawn_change", "player_set_spawn", "whitelist_toggle", "whitelist_state_update",
        "center", "bounds",
        "block_fade", "block_form", "block_grow", "block_spread", "leaves_decay",
        "moisture_change", "structure_grow",
        "io.papermc.paper.event.player.PlayerTradeEvent",
        "io.papermc.paper.event.player.PlayerPurchaseEvent"
    };

    private static final Map<String, Integer> FIELD_IDS = index(FIELD_NAMES);
    private static final Map<String, Integer> KNOWN_STRING_IDS = index(KNOWN_STRINGS);

    private PaperPayloadNbtCodec() {
    }

    public static EventPayload encode(CompoundTag tag) {
        Objects.requireNonNull(tag, "tag");

        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            writeCompound(tag, output);
        } catch (IOException e) {
            throw new AssertionError("Unexpected in-memory payload encoding failure.", e);
        }
        return EventPayload.takeOwnership(bytes.toByteArray());
    }

    static CompoundTag position(BlockPosition position) {
        Objects.requireNonNull(position, "position");
        var result = new CompoundTag();
        result.putInt("x", position.x());
        result.putInt("y", position.y());
        result.putInt("z", position.z());
        return result;
    }

    public static CompoundTag decode(EventPayload payload) throws IOException {
        try (
            var input = new DataInputStream(
                Objects.requireNonNull(payload, "payload").openStream()
            )
        ) {
            var result = readCompound(input);
            if (input.read() != -1) {
                throw new IOException("Trailing bytes after built-in payload.");
            }
            return result;
        }
    }

    private static void writeCompound(CompoundTag tag, DataOutputStream output) throws IOException {
        writeVarInt(tag.size(), output);
        for (var entry : tag.entrySet()) {
            writeFieldName(entry.getKey(), output);
            writeTag(entry.getValue(), output);
        }
    }

    private static CompoundTag readCompound(DataInputStream input) throws IOException {
        var result = new CompoundTag();
        var size = readLength(input);
        for (var i = 0; i < size; i++) {
            result.put(readFieldName(input), readTag(input));
        }
        return result;
    }

    private static void writeFieldName(String name, DataOutputStream output) throws IOException {
        var id = FIELD_IDS.get(name);
        if (id != null) {
            writeVarInt(id, output);
            return;
        }
        writeVarInt(0, output);
        writeUtf8(name, output);
    }

    private static String readFieldName(DataInputStream input) throws IOException {
        var id = readVarInt(input);
        if (id == 0) {
            return readUtf8(input);
        }
        if (id > FIELD_NAMES.length) {
            throw new IOException("Unknown built-in payload field ID: " + id);
        }
        return FIELD_NAMES[id - 1];
    }

    private static void writeTag(Tag tag, DataOutputStream output) throws IOException {
        switch (tag.getId()) {
            case Tag.TAG_BYTE -> {
                var value = tag.asByte().orElseThrow();
                if (value == 0) {
                    output.writeByte(TYPE_FALSE);
                } else if (value == 1) {
                    output.writeByte(TYPE_TRUE);
                } else {
                    output.writeByte(TYPE_BYTE);
                    output.writeByte(value);
                }
            }
            case Tag.TAG_SHORT -> {
                output.writeByte(TYPE_SHORT);
                writeVarInt(zigZag(tag.asShort().orElseThrow()), output);
            }
            case Tag.TAG_INT -> {
                output.writeByte(TYPE_INT);
                writeVarInt(zigZag(tag.asInt().orElseThrow()), output);
            }
            case Tag.TAG_LONG -> {
                output.writeByte(TYPE_LONG);
                writeVarLong(zigZag(tag.asLong().orElseThrow()), output);
            }
            case Tag.TAG_FLOAT -> {
                output.writeByte(TYPE_FLOAT);
                output.writeFloat(tag.asFloat().orElseThrow());
            }
            case Tag.TAG_DOUBLE -> {
                output.writeByte(TYPE_DOUBLE);
                output.writeDouble(tag.asDouble().orElseThrow());
            }
            case Tag.TAG_BYTE_ARRAY -> {
                output.writeByte(TYPE_BYTE_ARRAY);
                var value = tag.asByteArray().orElseThrow();
                writeVarInt(value.length, output);
                output.write(value);
            }
            case Tag.TAG_STRING -> writeStringTag(tag.asString().orElseThrow(), output);
            case Tag.TAG_LIST -> {
                output.writeByte(TYPE_LIST);
                var list = tag.asList().orElseThrow();
                writeVarInt(list.size(), output);
                for (var value : list) {
                    writeTag(value, output);
                }
            }
            case Tag.TAG_COMPOUND -> {
                output.writeByte(TYPE_COMPOUND);
                writeCompound(tag.asCompound().orElseThrow(), output);
            }
            case Tag.TAG_INT_ARRAY -> {
                output.writeByte(TYPE_INT_ARRAY);
                var values = tag.asIntArray().orElseThrow();
                writeVarInt(values.length, output);
                for (var value : values) {
                    writeVarInt(zigZag(value), output);
                }
            }
            case Tag.TAG_LONG_ARRAY -> {
                output.writeByte(TYPE_LONG_ARRAY);
                var values = tag.asLongArray().orElseThrow();
                writeVarInt(values.length, output);
                for (var value : values) {
                    writeVarLong(zigZag(value), output);
                }
            }
            default -> throw new IOException("Unsupported built-in payload tag type: " + tag.getId());
        }
    }

    private static Tag readTag(DataInputStream input) throws IOException {
        return switch (input.readUnsignedByte()) {
            case TYPE_FALSE -> ByteTag.ZERO;
            case TYPE_TRUE -> ByteTag.ONE;
            case TYPE_BYTE -> ByteTag.valueOf(input.readByte());
            case TYPE_SHORT -> ShortTag.valueOf((short) unZigZagInt(readVarInt(input)));
            case TYPE_INT -> IntTag.valueOf(unZigZagInt(readVarInt(input)));
            case TYPE_LONG -> LongTag.valueOf(unZigZagLong(readVarLong(input)));
            case TYPE_FLOAT -> FloatTag.valueOf(input.readFloat());
            case TYPE_DOUBLE -> DoubleTag.valueOf(input.readDouble());
            case TYPE_BYTE_ARRAY -> {
                var bytes = new byte[readLength(input)];
                input.readFully(bytes);
                yield new ByteArrayTag(bytes);
            }
            case TYPE_STRING -> StringTag.valueOf(readUtf8(input));
            case TYPE_LIST -> {
                var result = new ListTag();
                var size = readLength(input);
                for (var i = 0; i < size; i++) {
                    result.add(readTag(input));
                }
                yield result;
            }
            case TYPE_COMPOUND -> readCompound(input);
            case TYPE_INT_ARRAY -> {
                var values = new int[readLength(input)];
                for (var i = 0; i < values.length; i++) {
                    values[i] = unZigZagInt(readVarInt(input));
                }
                yield new IntArrayTag(values);
            }
            case TYPE_LONG_ARRAY -> {
                var values = new long[readLength(input)];
                for (var i = 0; i < values.length; i++) {
                    values[i] = unZigZagLong(readVarLong(input));
                }
                yield new LongArrayTag(values);
            }
            case TYPE_UUID_STRING -> StringTag.valueOf(readUuid(input).toString());
            case TYPE_MINECRAFT_KEY_STRING ->
                StringTag.valueOf("minecraft:" + readUtf8(input));
            case TYPE_KNOWN_STRING -> {
                var id = readVarInt(input);
                if (id < 1 || id > KNOWN_STRINGS.length) {
                    throw new IOException("Unknown built-in payload string ID: " + id);
                }
                yield StringTag.valueOf(KNOWN_STRINGS[id - 1]);
            }
            default -> throw new IOException("Unknown built-in payload tag type.");
        };
    }

    private static void writeStringTag(String value, DataOutputStream output) throws IOException {
        var knownId = KNOWN_STRING_IDS.get(value);
        if (knownId != null) {
            output.writeByte(TYPE_KNOWN_STRING);
            writeVarInt(knownId, output);
            return;
        }

        if (value.startsWith("minecraft:")) {
            output.writeByte(TYPE_MINECRAFT_KEY_STRING);
            writeUtf8(value.substring("minecraft:".length()), output);
            return;
        }

        var uuid = parseUuid(value);
        if (uuid != null) {
            output.writeByte(TYPE_UUID_STRING);
            output.writeLong(uuid.getMostSignificantBits());
            output.writeLong(uuid.getLeastSignificantBits());
            return;
        }

        output.writeByte(TYPE_STRING);
        writeUtf8(value, output);
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static UUID parseUuid(String value) {
        if (
            value.length() != 36
                || value.charAt(8) != '-'
                || value.charAt(13) != '-'
                || value.charAt(18) != '-'
                || value.charAt(23) != '-'
        ) {
            return null;
        }
        try {
            var uuid = UUID.fromString(value);
            return uuid.toString().equals(value) ? uuid : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void writeUtf8(String value, DataOutputStream output) throws IOException {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(bytes.length, output);
        output.write(bytes);
    }

    private static String readUtf8(DataInputStream input) throws IOException {
        var bytes = new byte[readLength(input)];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int readLength(DataInputStream input) throws IOException {
        var value = readVarInt(input);
        if (value < 0) {
            throw new IOException("Negative built-in payload length.");
        }
        return value;
    }

    private static void writeVarInt(int value, DataOutputStream output) throws IOException {
        while ((value & ~0x7f) != 0) {
            output.writeByte((value & 0x7f) | 0x80);
            value >>>= 7;
        }
        output.writeByte(value);
    }

    private static int readVarInt(DataInputStream input) throws IOException {
        var result = 0;
        for (var shift = 0; shift < 35; shift += 7) {
            var value = input.read();
            if (value < 0) {
                throw new EOFException("Unexpected end of built-in payload.");
            }
            result |= (value & 0x7f) << shift;
            if ((value & 0x80) == 0) {
                return result;
            }
        }
        throw new IOException("Built-in payload varint is too long.");
    }

    private static void writeVarLong(long value, DataOutputStream output) throws IOException {
        while ((value & ~0x7fL) != 0) {
            output.writeByte((int) (value & 0x7fL) | 0x80);
            value >>>= 7;
        }
        output.writeByte((int) value);
    }

    private static long readVarLong(DataInputStream input) throws IOException {
        var result = 0L;
        for (var shift = 0; shift < 70; shift += 7) {
            var value = input.read();
            if (value < 0) {
                throw new EOFException("Unexpected end of built-in payload.");
            }
            result |= (long) (value & 0x7f) << shift;
            if ((value & 0x80) == 0) {
                return result;
            }
        }
        throw new IOException("Built-in payload varlong is too long.");
    }

    private static int zigZag(int value) {
        return (value << 1) ^ (value >> 31);
    }

    private static long zigZag(long value) {
        return (value << 1) ^ (value >> 63);
    }

    private static int unZigZagInt(int value) {
        return (value >>> 1) ^ -(value & 1);
    }

    private static long unZigZagLong(long value) {
        return (value >>> 1) ^ -(value & 1L);
    }

    private static Map<String, Integer> index(String[] values) {
        var result = new HashMap<String, Integer>(values.length * 2);
        for (var i = 0; i < values.length; i++) {
            var previous = result.put(values[i], i + 1);
            if (previous != null) {
                throw new ExceptionInInitializerError("Duplicate compact payload value: " + values[i]);
            }
        }
        return Map.copyOf(result);
    }
}
