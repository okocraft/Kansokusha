package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.minecraft.nbt.CompoundTag;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Instant;

/**
 * The {@code kansokusha:ore_vein_found} event, recorded when a player breaks the first ore of a
 * naturally generated vein. Detection lives in the ore vein listener, which depends on the
 * configuration, so this event type is registered separately from the built-in listeners.
 */
@ApiStatus.Internal
@NotNullByDefault
public final class PaperOreVeinEvents {

    static final Key EVENT_TYPE = Key.key("kansokusha", "ore_vein_found");

    private PaperOreVeinEvents() {
    }

    static void register(KansokushaApi api) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
    }

    /**
     * @param ore       the broken ore, which still has its type
     * @param veinCount the number of ores in the vein, including the broken one
     */
    public static EventSubmission found(Instant occurredAt, Key serverKey, Player player, Block ore, int veinCount) {
        var payload = new CompoundTag();
        payload.putInt("vein_count", veinCount);
        return new EventSubmission(
            EVENT_TYPE,
            PayloadGeneration.FIRST,
            occurredAt,
            serverKey,
            PaperKansokusha.key(ore.getWorld().getKey()),
            PaperBuiltInSupport.position(ore),
            new PlayerActor(player.getUniqueId()),
            PaperBuiltInSupport.type(ore.getType()),
            PaperPayloadNbtCodec.encode(payload)
        );
    }
}
