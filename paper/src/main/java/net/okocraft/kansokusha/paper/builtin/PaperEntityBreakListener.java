package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import org.bukkit.Bukkit;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperEntityBreakListener implements PaperInFlightListener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "entity_break");
    static final String GENERIC_SOURCE_EVENT =
        "io.papermc.paper.event.entity.EntityBreakByEntityEvent";
    static final String HANGING_SOURCE_EVENT =
        "org.bukkit.event.hanging.HangingBreakByEntityEvent";

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final PaperInFlightMap<Event, Snapshot> genericInFlight =
        new PaperInFlightMap<>();
    private final PaperInFlightMap<HangingBreakByEntityEvent, Snapshot> hangingInFlight =
        new PaperInFlightMap<>();
    private volatile boolean genericCallbacksRegistered;

    private PaperEntityBreakListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperEntityBreakListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperEntityBreakListener register(KansokushaApi api, Key serverKey, Clock clock) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperEntityBreakListener(api, serverKey, clock);
    }

    /**
     * Registers callbacks for Paper 26.3+'s generic entity break event when that API is present.
     *
     * <p>Paper 26.2 does not expose {@code EntityBreakByEntityEvent}, so the generic side is
     * linked reflectively instead of raising the plugin's Paper baseline. The regular Bukkit
     * listener registration still owns {@link HangingBreakByEntityEvent}.</p>
     *
     * @return {@code true} when the generic Paper event exists and callbacks were registered
     */
    public synchronized boolean registerGenericCallbacks(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        if (this.genericCallbacksRegistered) {
            return true;
        }

        final Class<?> rawEventClass;
        try {
            rawEventClass = Class.forName(
                GENERIC_SOURCE_EVENT,
                false,
                PaperEntityBreakListener.class.getClassLoader()
            );
        } catch (ClassNotFoundException ignored) {
            return false;
        }

        if (!Event.class.isAssignableFrom(rawEventClass)
            || !Cancellable.class.isAssignableFrom(rawEventClass)) {
            throw new IllegalStateException(
                GENERIC_SOURCE_EVENT + " is not a cancellable Bukkit event."
            );
        }

        @SuppressWarnings("unchecked")
        var eventClass = (Class<? extends Event>) rawEventClass;
        var access = GenericEventAccess.forClass(rawEventClass);

        Bukkit.getPluginManager().registerEvent(
            eventClass,
            this,
            EventPriority.LOWEST,
            (ignored, event) -> this.captureGeneric(event, access),
            plugin,
            false
        );
        Bukkit.getPluginManager().registerEvent(
            eventClass,
            this,
            EventPriority.MONITOR,
            (ignored, event) -> this.finalizeGeneric(event),
            plugin,
            false
        );
        this.genericCallbacksRegistered = true;
        return true;
    }

    void captureGeneric(Event event) {
        Objects.requireNonNull(event, "event");
        this.captureGeneric(event, GenericEventAccess.forClass(event.getClass()));
    }

    void finalizeGeneric(Event event) {
        Objects.requireNonNull(event, "event");
        var snapshot = this.genericInFlight.remove(event);
        if (!(event instanceof Cancellable cancellable)) {
            throw new IllegalArgumentException("Generic entity break event must be cancellable.");
        }
        this.finalizeEvent(cancellable.isCancelled(), snapshot);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureHanging(HangingBreakByEntityEvent event) {
        Objects.requireNonNull(event, "event");
        if (this.genericCallbacksRegistered) {
            return;
        }

        var damageSource = event.getDamageSource();
        var remover = event.getRemover();
        this.hangingInFlight.put(
            event,
            this.snapshot(
                PaperEntityEventPayloadCodec.snapshotEntity(event.getEntity()),
                PaperEntityEventPayloadCodec.snapshotEntity(remover),
                remover instanceof Player player ? player : null,
                event.getCause().name().toLowerCase(Locale.ROOT),
                damageSource.getDamageType().getKey().toString(),
                damageSource.isIndirect(),
                HANGING_SOURCE_EVENT,
                true
            )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeHanging(HangingBreakByEntityEvent event) {
        Objects.requireNonNull(event, "event");
        if (this.genericCallbacksRegistered) {
            return;
        }
        this.finalizeEvent(event.isCancelled(), this.hangingInFlight.remove(event));
    }

    @Override
    public void clearInFlightState() {
        this.genericInFlight.clear();
        this.hangingInFlight.clear();
    }

    int inFlightCount() {
        return this.genericInFlight.size() + this.hangingInFlight.size();
    }

    private void captureGeneric(Event event, GenericEventAccess access) {
        var entity = access.entity(event);
        var hanging = entity instanceof Hanging;
        var damageSource = access.damageSource(event);
        var remover = access.remover(event);
        this.genericInFlight.put(
            event,
            this.snapshot(
                PaperEntityEventPayloadCodec.snapshotEntity(entity),
                PaperEntityEventPayloadCodec.snapshotEntity(remover),
                remover instanceof Player player ? player : null,
                access.cause(event).name().toLowerCase(Locale.ROOT),
                damageSource.getDamageType().getKey().toString(),
                damageSource.isIndirect(),
                hanging ? HANGING_SOURCE_EVENT : GENERIC_SOURCE_EVENT,
                hanging
            )
        );
    }

    private Snapshot snapshot(
        PaperEntityEventPayloadCodec.EntitySnapshot brokenEntity,
        PaperEntityEventPayloadCodec.EntitySnapshot breaker,
        @Nullable Player player,
        String cause,
        String damageType,
        boolean indirectDamage,
        String sourceEvent,
        boolean hanging
    ) {
        return new Snapshot(
            Instant.now(this.clock),
            this.serverKey,
            brokenEntity.worldKey(),
            new BlockPosition(
                (int) Math.floor(brokenEntity.x()),
                (int) Math.floor(brokenEntity.y()),
                (int) Math.floor(brokenEntity.z())
            ),
            player == null ? null : new PlayerSubject(player.getUniqueId()),
            PaperEntityEventPayloadCodec.encodeBreak(
                brokenEntity,
                breaker,
                cause,
                damageType,
                indirectDamage,
                sourceEvent,
                hanging
            )
        );
    }

    private void finalizeEvent(boolean cancelled, @Nullable Snapshot snapshot) {
        if (snapshot == null || cancelled) {
            return;
        }

        this.api.submit(
            new EventSubmission(
                EVENT_TYPE,
                PayloadGeneration.FIRST,
                snapshot.occurredAt(),
                snapshot.serverKey(),
                snapshot.worldKey(),
                snapshot.position(),
                snapshot.subject(),
                snapshot.payload()
            )
        );
    }

    private record Snapshot(
        Instant occurredAt,
        Key serverKey,
        Key worldKey,
        BlockPosition position,
        @Nullable PlayerSubject subject,
        EventPayload payload
    ) {
    }

    private record GenericEventAccess(
        Method entity,
        Method remover,
        Method damageSource,
        Method cause
    ) {

        static GenericEventAccess forClass(Class<?> eventClass) {
            try {
                return new GenericEventAccess(
                    eventClass.getMethod("getEntity"),
                    eventClass.getMethod("getRemover"),
                    eventClass.getMethod("getDamageSource"),
                    eventClass.getMethod("getCause")
                );
            } catch (NoSuchMethodException exception) {
                throw new IllegalArgumentException(
                    eventClass.getName() + " does not match EntityBreakByEntityEvent.",
                    exception
                );
            }
        }

        Entity entity(Event event) {
            return invoke(this.entity, event, Entity.class);
        }

        Entity remover(Event event) {
            return invoke(this.remover, event, Entity.class);
        }

        DamageSource damageSource(Event event) {
            return invoke(this.damageSource, event, DamageSource.class);
        }

        Enum<?> cause(Event event) {
            return invoke(this.cause, event, Enum.class);
        }

        private static <T> T invoke(Method method, Event event, Class<T> type) {
            try {
                return type.cast(method.invoke(event));
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException(
                    "Cannot access " + method.getDeclaringClass().getName() + "#" + method.getName(),
                    exception
                );
            } catch (InvocationTargetException exception) {
                var cause = exception.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException(
                    "Generic entity break callback failed.",
                    cause
                );
            }
        }
    }
}
