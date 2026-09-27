package net.okocraft.kansokusha.common.search;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.common.search.query.SearchQuery.ActorKind;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

@ApiStatus.Internal
@NotNullByDefault
public record EventDetail(
    UUID eventId,
    Key eventType,
    PayloadGeneration payloadGeneration,
    Instant occurredAt,
    Optional<Key> server,
    Optional<Key> world,
    OptionalInt x,
    OptionalInt y,
    OptionalInt z,
    Optional<ActorKind> actorKind,
    Optional<UUID> actorUuid,
    Optional<String> actorName,
    Optional<Key> actorType,
    Optional<Key> targetType,
    Instant expiresAt,
    Optional<String> searchText
) {

    public EventDetail {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(payloadGeneration, "payloadGeneration");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(x, "x");
        Objects.requireNonNull(y, "y");
        Objects.requireNonNull(z, "z");
        Objects.requireNonNull(actorKind, "actorKind");
        Objects.requireNonNull(actorUuid, "actorUuid");
        Objects.requireNonNull(actorName, "actorName");
        Objects.requireNonNull(actorType, "actorType");
        Objects.requireNonNull(targetType, "targetType");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(searchText, "searchText");
    }
}
