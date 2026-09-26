package net.okocraft.kansokusha.paper.builtin;

import com.destroystokyo.paper.event.server.WhitelistToggleEvent;
import io.papermc.paper.event.server.WhitelistStateUpdateEvent;
import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
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
    private final PaperInFlightMap<WhitelistToggleEvent, ToggleSnapshot> toggleInFlight =
        new PaperInFlightMap<>();
    private final PaperInFlightMap<WhitelistStateUpdateEvent, ProfileSnapshot> profileInFlight =
        new PaperInFlightMap<>();

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

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureToggle(WhitelistToggleEvent event) {
        Objects.requireNonNull(event, "event");

        this.toggleInFlight.put(event, new ToggleSnapshot(
            this.clock.instant(),
            this.whitelistEnabled.getAsBoolean(),
            event.isEnabled()
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeToggle(WhitelistToggleEvent event) {
        Objects.requireNonNull(event, "event");

        var snapshot = this.toggleInFlight.remove(event);
        if (snapshot == null || snapshot.before() == snapshot.after()) {
            return;
        }

        this.api.submit(new EventSubmission(
            EVENT_TYPE,
            PayloadGeneration.FIRST,
            snapshot.occurredAt(),
            this.serverKey,
            null,
            null,
            null,
            PaperAdministrativePayloadCodec.encodeWhitelistToggle(
                snapshot.before(),
                snapshot.after()
            )
        ));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureProfile(WhitelistStateUpdateEvent event) {
        Objects.requireNonNull(event, "event");

        var profile = event.getPlayerProfile();
        this.profileInFlight.put(event, new ProfileSnapshot(
            this.clock.instant(),
            event.getPlayer().isWhitelisted(),
            event.getStatus() == WhitelistStateUpdateEvent.WhitelistStatus.ADDED,
            profile.getId(),
            profile.getName()
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeProfile(WhitelistStateUpdateEvent event) {
        Objects.requireNonNull(event, "event");

        var snapshot = this.profileInFlight.remove(event);
        if (
            snapshot == null
                || event.isCancelled()
                || snapshot.before() == snapshot.added()
        ) {
            return;
        }

        this.api.submit(new EventSubmission(
            EVENT_TYPE,
            PayloadGeneration.FIRST,
            snapshot.occurredAt(),
            this.serverKey,
            null,
            null,
            null,
            PaperAdministrativePayloadCodec.encodeWhitelistProfileChange(
                snapshot.added(),
                snapshot.before(),
                snapshot.profileId(),
                snapshot.profileName()
            )
        ));
    }

    int inFlightCount() {
        return this.toggleInFlight.size() + this.profileInFlight.size();
    }

    private record ToggleSnapshot(Instant occurredAt, boolean before, boolean after) {
    }

    private record ProfileSnapshot(
        Instant occurredAt,
        boolean before,
        boolean added,
        @Nullable UUID profileId,
        @Nullable String profileName
    ) {
    }
}
