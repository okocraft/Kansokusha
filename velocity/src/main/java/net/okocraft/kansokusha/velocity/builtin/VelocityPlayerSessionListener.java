package net.okocraft.kansokusha.velocity.builtin;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.RegistrationOutcome;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ApiStatus.Internal
@NotNullByDefault
public final class VelocityPlayerSessionListener {

    static final Key POST_LOGIN_EVENT_TYPE =
        Key.key("kansokusha", "velocity_post_login");
    static final Key DISCONNECT_EVENT_TYPE =
        Key.key("kansokusha", "velocity_disconnect");
    static final Key BACKEND_KICK_EVENT_TYPE =
        Key.key("kansokusha", "backend_kick");
    static final Key PROXY_SERVER_KEY =
        Key.key("kansokusha", "velocity-proxy");

    private static final List<EventTypeDefinition> DEFINITIONS = List.of(
        new EventTypeDefinition(POST_LOGIN_EVENT_TYPE, PayloadGeneration.FIRST),
        new EventTypeDefinition(DISCONNECT_EVENT_TYPE, PayloadGeneration.FIRST),
        new EventTypeDefinition(BACKEND_KICK_EVENT_TYPE, PayloadGeneration.FIRST)
    );

    private final KansokushaApi api;
    private final Logger logger;
    private final Clock clock;
    private final Set<String> warnedServerNames = ConcurrentHashMap.newKeySet();

    private VelocityPlayerSessionListener(KansokushaApi api, Logger logger, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static VelocityPlayerSessionListener register(KansokushaApi api, Logger logger) {
        return register(api, logger, Clock.systemUTC());
    }

    static VelocityPlayerSessionListener register(
        KansokushaApi api,
        Logger logger,
        Clock clock
    ) {
        Objects.requireNonNull(api, "api");
        for (var definition : DEFINITIONS) {
            var outcome = api.registerEventType(definition);
            if (
                outcome != RegistrationOutcome.REGISTERED
                    && outcome != RegistrationOutcome.ALREADY_REGISTERED
            ) {
                throw new IllegalStateException(
                    "Could not register built-in event type "
                        + definition.key()
                        + ": "
                        + outcome
                );
            }
        }
        return new VelocityPlayerSessionListener(api, logger, clock);
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Objects.requireNonNull(event, "event");

        var player = event.getPlayer();
        var remoteAddress = player.getRemoteAddress();
        var virtualHost = player.getVirtualHost().orElse(null);
        var rawVirtualHost = player.getRawVirtualHost().orElse(null);

        this.api.submit(
            new EventSubmission(
                POST_LOGIN_EVENT_TYPE,
                PayloadGeneration.FIRST,
                this.clock.instant(),
                PROXY_SERVER_KEY,
                null,
                null,
                new PlayerSubject(player.getUniqueId()),
                VelocityPlayerSessionPayloadCodec.encodePostLogin(
                    player.getUsername(),
                    remoteAddress,
                    virtualHost,
                    rawVirtualHost
                )
            )
        );
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Objects.requireNonNull(event, "event");

        var player = event.getPlayer();
        Key currentBackendKey = null;
        var currentServer = player.getCurrentServer();
        if (currentServer.isPresent()) {
            currentBackendKey = this.serverKey(currentServer.get().getServer());
        }

        this.api.submit(
            new EventSubmission(
                DISCONNECT_EVENT_TYPE,
                PayloadGeneration.FIRST,
                this.clock.instant(),
                PROXY_SERVER_KEY,
                null,
                null,
                new PlayerSubject(player.getUniqueId()),
                VelocityPlayerSessionPayloadCodec.encodeDisconnect(
                    player.getUsername(),
                    event.getLoginStatus(),
                    currentBackendKey
                )
            )
        );
    }

    @Subscribe(priority = Short.MIN_VALUE)
    public void onKickedFromServer(KickedFromServerEvent event) {
        Objects.requireNonNull(event, "event");

        var sourceServerKey = this.serverKey(event.getServer());
        if (sourceServerKey == null) {
            return;
        }

        var result = event.getResult();
        var action = finalAction(result, event.kickedDuringServerConnect());
        Key redirectTarget = null;
        if (result instanceof KickedFromServerEvent.RedirectPlayer redirect) {
            redirectTarget = this.serverKey(redirect.getServer());
            if (redirectTarget == null) {
                return;
            }
        }

        this.api.submit(
            new EventSubmission(
                BACKEND_KICK_EVENT_TYPE,
                PayloadGeneration.FIRST,
                this.clock.instant(),
                sourceServerKey,
                null,
                null,
                new PlayerSubject(event.getPlayer().getUniqueId()),
                VelocityPlayerSessionPayloadCodec.encodeBackendKick(
                    event.getServerKickReason().orElse(null),
                    event.kickedDuringServerConnect(),
                    action,
                    redirectTarget,
                    finalMessage(result)
                )
            )
        );
    }

    private @Nullable Key serverKey(RegisteredServer server) {
        var name = server.getServerInfo().getName();
        var key = VelocityServerKeyCodec.encode(name);
        if (key.isEmpty() && this.warnedServerNames.add(name)) {
            this.logger.warn(
                "Velocity events referencing backend '{}' are not recorded: the lower-cased "
                    + "name is not a valid key value (allowed characters: a-z 0-9 _ . - /).",
                name
            );
        }
        return key.orElse(null);
    }

    private static VelocityPlayerSessionPayloadCodec.ProxyAction finalAction(
        KickedFromServerEvent.ServerKickResult result,
        boolean kickedDuringServerConnect
    ) {
        if (result instanceof KickedFromServerEvent.DisconnectPlayer) {
            return VelocityPlayerSessionPayloadCodec.ProxyAction.DISCONNECT;
        }
        if (result instanceof KickedFromServerEvent.RedirectPlayer) {
            return VelocityPlayerSessionPayloadCodec.ProxyAction.REDIRECT;
        }
        if (result instanceof KickedFromServerEvent.Notify) {
            return kickedDuringServerConnect
                ? VelocityPlayerSessionPayloadCodec.ProxyAction.NOTIFY
                : VelocityPlayerSessionPayloadCodec.ProxyAction.DISCONNECT;
        }
        throw new IllegalArgumentException("Unknown ServerKickResult: " + result.getClass());
    }

    private static @Nullable net.kyori.adventure.text.Component finalMessage(
        KickedFromServerEvent.ServerKickResult result
    ) {
        if (result instanceof KickedFromServerEvent.DisconnectPlayer disconnect) {
            return disconnect.getReasonComponent();
        }
        if (result instanceof KickedFromServerEvent.RedirectPlayer redirect) {
            return redirect.getMessageComponent();
        }
        if (result instanceof KickedFromServerEvent.Notify notify) {
            return notify.getMessageComponent();
        }
        throw new IllegalArgumentException("Unknown ServerKickResult: " + result.getClass());
    }
}
