package net.okocraft.kansokusha.paper.builtin;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.util.Objects;

/**
 * Records the original content observed by Paper's modern chat event.
 */
@ApiStatus.Internal
@NotNullByDefault
public final class PaperChatListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "paper_chat");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;

    private PaperChatListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperChatListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperChatListener register(KansokushaApi api, Key serverKey, Clock clock) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperChatListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void record(AsyncChatEvent event) {
        Objects.requireNonNull(event, "event");

        var occurredAt = this.clock.instant();
        var player = event.getPlayer();
        var actor = new PlayerActor(player.getUniqueId());
        var payload = PaperCommunicationPayloadCodec.encodeChat(event.originalMessage());

        this.api.submit(
            new EventSubmission(
                EVENT_TYPE,
                PayloadGeneration.FIRST,
                occurredAt,
                this.serverKey,
                null,
                null,
                actor,
                null,
                payload
            )
        );
    }
}
