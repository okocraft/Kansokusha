package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.block.Block;
import org.bukkit.event.block.TNTPrimeEvent;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.WeakHashMap;

@NotNullByDefault
final class PaperExplosionTntCorrelation {

    private static final Map<KansokushaApi, Coordinator> COORDINATORS = new WeakHashMap<>();

    private PaperExplosionTntCorrelation() {
    }

    static void beginModernDestroy(KansokushaApi api, List<Candidate> candidates) {
        coordinator(api).begin(OperationKind.MODERN_DESTROY, candidates);
    }

    static void beginModernTrigger(KansokushaApi api, List<Candidate> candidates) {
        coordinator(api).begin(OperationKind.MODERN_TRIGGER, candidates);
    }

    static void beginLegacyDragon(KansokushaApi api, List<Candidate> candidates) {
        coordinator(api).begin(OperationKind.LEGACY_DRAGON, candidates);
    }

    static void captureModern(KansokushaApi api, TNTPrimeEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.getCause() != TNTPrimeEvent.PrimeCause.EXPLOSION) {
            return;
        }
        var coordinator = existing(api);
        if (coordinator != null) {
            coordinator.captureModern(event);
        }
    }

    static Decision finalizeModern(KansokushaApi api, TNTPrimeEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.getCause() != TNTPrimeEvent.PrimeCause.EXPLOSION) {
            return Decision.UNTRACKED;
        }
        var coordinator = existing(api);
        return coordinator == null ? Decision.UNTRACKED : coordinator.finalizeModern(event);
    }

    @SuppressWarnings({"deprecation", "removal"})
    static boolean captureLegacy(
        KansokushaApi api,
        com.destroystokyo.paper.event.block.TNTPrimeEvent event
    ) {
        Objects.requireNonNull(event, "event");
        if (
            event.getReason()
                != com.destroystokyo.paper.event.block.TNTPrimeEvent.PrimeReason.EXPLOSION
        ) {
            return false;
        }
        var coordinator = existing(api);
        return coordinator != null && coordinator.captureLegacy(event);
    }

    @SuppressWarnings({"deprecation", "removal"})
    static Decision finalizeLegacy(
        KansokushaApi api,
        com.destroystokyo.paper.event.block.TNTPrimeEvent event
    ) {
        Objects.requireNonNull(event, "event");
        if (
            event.getReason()
                != com.destroystokyo.paper.event.block.TNTPrimeEvent.PrimeReason.EXPLOSION
        ) {
            return Decision.UNTRACKED;
        }
        var coordinator = existing(api);
        return coordinator == null ? Decision.UNTRACKED : coordinator.finalizeLegacy(event);
    }

    static int pendingCount(KansokushaApi api) {
        var coordinator = existing(api);
        return coordinator == null ? 0 : coordinator.pendingCount();
    }

    static void clear(KansokushaApi api) {
        Objects.requireNonNull(api, "api");
        synchronized (COORDINATORS) {
            COORDINATORS.remove(api);
        }
    }

    static Candidate candidate(
        Key worldKey,
        BlockPosition position,
        int occurrences,
        @Nullable EventSubmission submission,
        boolean recordTntPrime
    ) {
        return candidate(
            worldKey,
            position,
            occurrences,
            submission,
            recordTntPrime,
            false
        );
    }

    static Candidate candidate(
        Key worldKey,
        BlockPosition position,
        int occurrences,
        @Nullable EventSubmission submission,
        boolean recordTntPrime,
        boolean listedInExplosionWorld
    ) {
        return new Candidate(
            worldKey,
            position,
            occurrences,
            submission,
            recordTntPrime,
            listedInExplosionWorld
        );
    }

    record Decision(boolean tracked, boolean recordTntPrime) {
        private static final Decision UNTRACKED = new Decision(false, false);
        private static final Decision TRACKED_NO_PRIME = new Decision(true, false);
        private static final Decision TRACKED_PRIME = new Decision(true, true);
    }

    record Candidate(
        Key worldKey,
        BlockPosition position,
        int occurrences,
        @Nullable EventSubmission submission,
        boolean recordTntPrime,
        boolean listedInExplosionWorld
    ) {
        Candidate {
            Objects.requireNonNull(worldKey, "worldKey");
            Objects.requireNonNull(position, "position");
            if (occurrences < 1) {
                throw new IllegalArgumentException("occurrences must be positive");
            }
        }
    }

    private static Coordinator coordinator(KansokushaApi api) {
        Objects.requireNonNull(api, "api");
        synchronized (COORDINATORS) {
            return COORDINATORS.computeIfAbsent(api, Coordinator::new);
        }
    }

    private static @Nullable Coordinator existing(KansokushaApi api) {
        Objects.requireNonNull(api, "api");
        synchronized (COORDINATORS) {
            return COORDINATORS.get(api);
        }
    }

    private enum OperationKind {
        MODERN_DESTROY,
        MODERN_TRIGGER,
        LEGACY_DRAGON
    }

    private static final class Item {
        private final BlockKey key;
        private final @Nullable EventSubmission submission;
        private final boolean recordTntPrime;
        private final boolean listedInExplosionWorld;
        private int remaining;
        private boolean active = true;

        private Item(Candidate candidate) {
            this.key = new BlockKey(candidate.worldKey(), candidate.position());
            this.submission = candidate.submission();
            this.recordTntPrime = candidate.recordTntPrime();
            this.listedInExplosionWorld =
                candidate.listedInExplosionWorld();
            this.remaining = candidate.occurrences();
        }
    }

    private static final class Operation {
        private final OperationKind kind;
        private final List<Item> items = new ArrayList<>();
        private final Set<BlockKey> submittedBlocks = new HashSet<>();

        private Operation(OperationKind kind, List<Candidate> candidates) {
            this.kind = kind;
            for (var candidate : candidates) {
                this.items.add(new Item(candidate));
            }
        }
    }

    private record Binding(Operation operation, Item item, boolean targetPresent) {
    }

    private record Finalization(Decision decision, @Nullable EventSubmission submission) {
    }

    private static final class Coordinator {
        private final KansokushaApi api;
        private final Map<Thread, ArrayDeque<Operation>> stacks = new HashMap<>();
        private final Map<TNTPrimeEvent, Binding> modernBindings = new IdentityHashMap<>();
        private final Map<TNTPrimeEvent, Decision> modernDecisions = new WeakHashMap<>();
        @SuppressWarnings({"deprecation", "removal"})
        private final Map<com.destroystokyo.paper.event.block.TNTPrimeEvent, Binding>
            legacyBindings = new IdentityHashMap<>();
        @SuppressWarnings({"deprecation", "removal"})
        private final Map<com.destroystokyo.paper.event.block.TNTPrimeEvent, Decision>
            legacyDecisions = new WeakHashMap<>();

        private Coordinator(KansokushaApi api) {
            this.api = api;
        }

        private synchronized void begin(OperationKind kind, List<Candidate> candidates) {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(candidates, "candidates");
            if (candidates.isEmpty()) {
                return;
            }
            this.stacks.computeIfAbsent(
                Thread.currentThread(),
                ignored -> new ArrayDeque<>()
            ).addLast(new Operation(kind, List.copyOf(candidates)));
        }

        private synchronized void captureModern(TNTPrimeEvent event) {
            if (this.modernBindings.containsKey(event)) {
                return;
            }
            var binding = findBinding(event.getBlock(), false, true);
            if (binding != null) {
                this.modernBindings.put(event, binding);
            }
        }

        @SuppressWarnings({"deprecation", "removal"})
        private synchronized boolean captureLegacy(
            com.destroystokyo.paper.event.block.TNTPrimeEvent event
        ) {
            var binding = this.legacyBindings.get(event);
            if (binding == null) {
                binding = findBinding(
                    event.getBlock(),
                    true,
                    !event.getBlock().getType().isAir()
                );
                if (binding != null) {
                    this.legacyBindings.put(event, binding);
                }
            }
            return binding != null && binding.item().active && binding.item().recordTntPrime;
        }

        private Decision finalizeModern(TNTPrimeEvent event) {
            Finalization finalization;
            synchronized (this) {
                var cached = this.modernDecisions.get(event);
                if (cached != null) {
                    return cached;
                }
                finalization = finalizeBound(
                    this.modernBindings.remove(event),
                    event.isCancelled()
                );
                this.modernDecisions.put(event, finalization.decision());
            }
            if (finalization.submission() != null) {
                this.api.submit(finalization.submission());
            }
            return finalization.decision();
        }

        @SuppressWarnings({"deprecation", "removal"})
        private Decision finalizeLegacy(
            com.destroystokyo.paper.event.block.TNTPrimeEvent event
        ) {
            Finalization finalization;
            synchronized (this) {
                var cached = this.legacyDecisions.get(event);
                if (cached != null) {
                    return cached;
                }
                finalization = finalizeBound(
                    this.legacyBindings.remove(event),
                    event.isCancelled()
                );
                this.legacyDecisions.put(event, finalization.decision());
            }
            if (finalization.submission() != null) {
                this.api.submit(finalization.submission());
            }
            return finalization.decision();
        }

        private synchronized int pendingCount() {
            var result = 0;
            for (var stack : this.stacks.values()) {
                for (var operation : stack) {
                    result += operation.items.size();
                }
            }
            return result;
        }

        private @Nullable Binding findBinding(
            Block block,
            boolean legacy,
            boolean targetPresent
        ) {
            var stack = this.stacks.get(Thread.currentThread());
            if (stack == null) {
                return null;
            }
            var key = new BlockKey(
                PaperKansokusha.key(block.getWorld().getKey()),
                new BlockPosition(block.getX(), block.getY(), block.getZ())
            );
            var iterator = stack.descendingIterator();
            while (iterator.hasNext()) {
                var operation = iterator.next();
                if (
                    legacy
                        ? operation.kind != OperationKind.LEGACY_DRAGON
                        : operation.kind == OperationKind.LEGACY_DRAGON
                ) {
                    continue;
                }
                for (var item : operation.items) {
                    if (
                        item.key.equals(key)
                            && item.active
                            && item.remaining > 0
                    ) {
                        return new Binding(operation, item, targetPresent);
                    }
                }
            }
            return null;
        }

        private Finalization finalizeBound(@Nullable Binding binding, boolean cancelled) {
            if (binding == null) {
                return new Finalization(Decision.UNTRACKED, null);
            }

            var operation = binding.operation();
            var item = binding.item();
            if (!item.active) {
                prune(Thread.currentThread());
                return new Finalization(Decision.TRACKED_NO_PRIME, null);
            }

            if (cancelled) {
                item.remaining--;
                if (item.remaining <= 0) {
                    deactivate(operation, item);
                }
                prune(Thread.currentThread());
                return new Finalization(Decision.TRACKED_NO_PRIME, null);
            }

            if (operation.kind == OperationKind.MODERN_TRIGGER) {
                item.remaining--;
                if (item.remaining <= 0) {
                    deactivate(operation, item);
                }
                prune(Thread.currentThread());
                return new Finalization(Decision.TRACKED_NO_PRIME, null);
            }

            var key = item.key;
            var recordTntPrime = item.recordTntPrime;
            EventSubmission submission = null;
            if (
                binding.targetPresent()
                    && item.submission != null
                    && operation.submittedBlocks.add(key)
            ) {
                submission = item.submission;
            }
            deactivate(operation, item);
            if (binding.targetPresent()) {
                if (operation.kind == OperationKind.LEGACY_DRAGON) {
                    invalidateSameOperation(operation, key);
                }
                invalidateOtherOperations(Thread.currentThread(), operation, key);
            }
            prune(Thread.currentThread());
            return new Finalization(
                recordTntPrime ? Decision.TRACKED_PRIME : Decision.TRACKED_NO_PRIME,
                submission
            );
        }

        private static void deactivate(Operation operation, Item item) {
            item.active = false;
            operation.items.remove(item);
        }

        private static void invalidateSameOperation(
            Operation operation,
            BlockKey changedBlock
        ) {
            operation.items.removeIf(item -> {
                if (
                    item.listedInExplosionWorld
                        && item.key.equals(changedBlock)
                ) {
                    item.active = false;
                    return true;
                }
                return false;
            });
        }

        private void invalidateOtherOperations(
            Thread thread,
            Operation acceptedOperation,
            BlockKey changedBlock
        ) {
            var stack = this.stacks.get(thread);
            if (stack == null) {
                return;
            }
            for (var operation : stack) {
                if (operation == acceptedOperation) {
                    continue;
                }
                operation.items.removeIf(item -> {
                    if (item.key.equals(changedBlock)) {
                        item.active = false;
                        return true;
                    }
                    return false;
                });
            }
        }

        private void prune(Thread thread) {
            var stack = this.stacks.get(thread);
            if (stack == null) {
                return;
            }
            var retained = new ArrayList<Operation>(stack.size());
            for (var operation : stack) {
                if (!operation.items.isEmpty()) {
                    retained.add(operation);
                }
            }
            stack.clear();
            stack.addAll(retained);
            if (stack.isEmpty()) {
                this.stacks.remove(thread);
            }
        }
    }
}
