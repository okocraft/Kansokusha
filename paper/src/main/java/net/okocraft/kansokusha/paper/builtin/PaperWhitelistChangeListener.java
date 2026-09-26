package net.okocraft.kansokusha.paper.builtin;

import com.destroystokyo.paper.event.server.WhitelistToggleEvent;
import io.papermc.paper.event.server.WhitelistStateUpdateEvent;
import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Records whitelist state changes.
 *
 * <p>{@link WhitelistToggleEvent} is the Paper global enable/disable boundary, while
 * {@link WhitelistStateUpdateEvent} is the canonical Paper profile add/remove boundary. The two
 * actions share one Kansokusha event type but are kept distinct in the payload.</p>
 */
@ApiStatus.Internal
@NotNullByDefault
public final class PaperWhitelistChangeListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "whitelist_change");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final BooleanSupplier whitelistEnabled;

    private PaperWhitelistChangeListener(
        KansokushaApi api,
        Key serverKey,
        Clock clock,
        BooleanSupplier whitelistEnabled
    ) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.whitelistEnabled = Objects.requireNonNull(whitelistEnabled, "whitelistEnabled");
    }

    public static PaperWhitelistChangeListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC(), Bukkit::hasWhitelist);
    }

    static PaperWhitelistChangeListener register(
        KansokushaApi api,
        Key serverKey,
        Clock clock,
        BooleanSupplier whitelistEnabled
    ) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperWhitelistChangeListener(api, serverKey, clock, whitelistEnabled);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordToggle(WhitelistToggleEvent event) {
        Objects.requireNonNull(event, "event");

        var before = this.whitelistEnabled.getAsBoolean();
        var after = event.isEnabled();
        if (before == after) {
            return;
        }

        this.submit(PaperAdministrativePayloadCodec.encodeWhitelistToggle(before, after));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordProfile(WhitelistStateUpdateEvent event) {
        Objects.requireNonNull(event, "event");

        var before = event.getPlayer().isWhitelisted();
        var added = event.getStatus() == WhitelistStateUpdateEvent.WhitelistStatus.ADDED;
        if (before == added) {
            return;
        }

        var profile = event.getPlayerProfile();
        this.submit(PaperAdministrativePayloadCodec.encodeWhitelistProfileChange(
            added,
            before,
            profile.getId(),
            profile.getName()
        ));
    }

    private void submit(EventPayload payload) {
        this.api.submit(new EventSubmission(
            EVENT_TYPE,
            PayloadGeneration.FIRST,
            this.clock.instant(),
            this.serverKey,
            null,
            null,
            null,
            null,
            payload
        ));
    }
}
