package net.okocraft.kansokusha.paper.inspection;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@NotNullByDefault
public final class InspectionSessionManager {

    public static final String PERMISSION = "kansokusha.command.inspect";

    private final ConcurrentMap<UUID, Boolean> enabledPlayers = new ConcurrentHashMap<>();

    public boolean isEnabled(UUID playerId) {
        return this.enabledPlayers.containsKey(Objects.requireNonNull(playerId, "playerId"));
    }

    /**
     * Returns whether inspection is enabled for a player after rechecking the runtime permission.
     * A player that lost the permission is removed from the current inspection session.
     */
    public boolean isEnabled(Player player) {
        Objects.requireNonNull(player, "player");

        var playerId = player.getUniqueId();
        if (!player.hasPermission(PERMISSION)) {
            this.disable(playerId);
            return false;
        }

        return this.isEnabled(playerId);
    }

    public boolean enable(UUID playerId) {
        return this.enabledPlayers.putIfAbsent(
            Objects.requireNonNull(playerId, "playerId"),
            Boolean.TRUE
        ) == null;
    }

    public boolean disable(UUID playerId) {
        return this.enabledPlayers.remove(Objects.requireNonNull(playerId, "playerId")) != null;
    }

    /**
     * Atomically toggles inspection for one player and returns the new state.
     */
    public boolean toggle(UUID playerId) {
        return this.enabledPlayers.compute(
            Objects.requireNonNull(playerId, "playerId"),
            (ignored, enabled) -> enabled == null ? Boolean.TRUE : null
        ) != null;
    }

    public void clear() {
        this.enabledPlayers.clear();
    }
}
