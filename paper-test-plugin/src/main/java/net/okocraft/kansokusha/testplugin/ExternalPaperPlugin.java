package net.okocraft.kansokusha.testplugin;

import io.papermc.paper.event.block.PlayerShearBlockEvent;
import io.papermc.paper.event.player.PlayerFlowerPotManipulateEvent;
import net.okocraft.kansokusha.api.Kansokusha;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.RegistrationOutcome;
import net.okocraft.kansokusha.api.SubmissionOutcome;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.UUID;

public final class ExternalPaperPlugin extends JavaPlugin {

    private static final NamespacedKey EVENT_TYPE =
        new NamespacedKey("fixture", "custom_event");

    private Path resultFile;

    @Override
    public void onEnable() {
        this.resultFile = Path.of(System.getProperty("kansokusha.external-api-fixture.result"));

        try {
            var result = registerAndSubmit();
            verifyBuiltInListenerWiring();
            verifyBuiltInPlatformSemantics();
            verifyWaterEvaporationBucketSemantics();
            Runtime.getRuntime().addShutdownHook(
                new Thread(() -> verifyAfterShutdown(result), "kansokusha-external-api-fixture")
            );
        } catch (Throwable failure) {
            writeFailure(failure);
        } finally {
            Bukkit.getScheduler().runTask(this, Bukkit::shutdown);
        }
    }

    private Result registerAndSubmit() {
        var api = Kansokusha.api();
        var definition = PaperKansokusha.eventType(EVENT_TYPE, PayloadGeneration.FIRST);
        if (api.registerEventType(definition) != RegistrationOutcome.REGISTERED) {
            throw new AssertionError("External event type registration did not succeed.");
        }

        var submission = new EventSubmission(
            PaperKansokusha.key(EVENT_TYPE),
            PayloadGeneration.FIRST,
            Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.MILLIS),
            api.localServerKey().orElseThrow(),
            null,
            null,
            null,
            EventPayload.copyOf(new byte[]{1, 2, 3})
        );

        if (api.submit(submission) != SubmissionOutcome.ACCEPTED) {
            throw new AssertionError("External event submission was not accepted.");
        }
        if (!EVENT_TYPE.equals(PaperKansokusha.namespacedKey(submission.eventType()))) {
            throw new AssertionError("NamespacedKey conversion was not lossless.");
        }

        return new Result(api, definition, submission);
    }

    private void verifyBuiltInListenerWiring() {
        assertRegisteredListener(
            "net.okocraft.kansokusha.paper.builtin.PaperSignChangeListener",
            SignChangeEvent.getHandlerList()
        );
        assertRegisteredListener(
            "net.okocraft.kansokusha.paper.builtin.PaperBucketListener",
            PlayerBucketEmptyEvent.getHandlerList()
        );
        assertRegisteredListener(
            "net.okocraft.kansokusha.paper.builtin.PaperBucketListener",
            PlayerBucketFillEvent.getHandlerList()
        );
        assertRegisteredListener(
            "net.okocraft.kansokusha.paper.builtin.PaperBlockHarvestListener",
            PlayerHarvestBlockEvent.getHandlerList()
        );
        assertRegisteredListener(
            "net.okocraft.kansokusha.paper.builtin.PaperBlockHarvestListener",
            PlayerShearBlockEvent.getHandlerList()
        );
        assertRegisteredListener(
            "net.okocraft.kansokusha.paper.builtin.PaperFlowerPotChangeListener",
            PlayerFlowerPotManipulateEvent.getHandlerList()
        );
    }

    private static void assertRegisteredListener(String listenerClass, HandlerList handlers) {
        var registered = Arrays.stream(handlers.getRegisteredListeners())
            .anyMatch(listener ->
                listener.getPlugin().getName().equals("Kansokusha")
                    && listener.getListener().getClass().getName().equals(listenerClass)
            );
        if (!registered) {
            throw new AssertionError("Kansokusha listener was not registered: " + listenerClass);
        }
    }

    private void verifyBuiltInPlatformSemantics() throws ReflectiveOperationException {
        var world = Bukkit.getWorlds().getFirst();
        var server = invoke(this.getServer(), "getServer");
        var level = invoke(world, "getHandle");
        var player = newServerPlayer(server, level);
        var bukkitPlayer = (Player) invoke(player, "getBukkitEntity");
        var gameMode = field(player, "gameMode");

        var probe = new PlatformEventProbe();
        this.getServer().getPluginManager().registerEvents(probe, this);

        int x = 128;
        int y = Math.max(world.getMinHeight() + 8, 80);
        int z = 128;
        invoke(player, "setPos", x + 0.5, (double) y, z + 0.5);

        var breakBlock = world.getBlockAt(x, y, z);
        breakBlock.setType(Material.STONE, false);
        bukkitPlayer.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        var beforeBreak = probe.snapshot();
        var breakPos = blockPos(x, y, z);
        if (!Boolean.TRUE.equals(invoke(gameMode, "destroyBlock", breakPos))) {
            throw new AssertionError("Fixture normal block break did not succeed.");
        }
        probe.assertDelta("normal break", beforeBreak, 1, 0, 0);

        var harvestBlock = world.getBlockAt(x + 1, y, z);
        harvestBlock.setType(Material.SWEET_BERRY_BUSH, false);
        var ageable = (Ageable) harvestBlock.getBlockData();
        ageable.setAge(ageable.getMaximumAge());
        harvestBlock.setBlockData(ageable, false);
        bukkitPlayer.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        var beforeHarvest = probe.snapshot();
        useItemOn(gameMode, player, level, x + 1, y, z);
        probe.assertDelta("berry harvest", beforeHarvest, 0, 1, 0);

        var shearBlock = world.getBlockAt(x + 2, y, z);
        shearBlock.setType(Material.PUMPKIN, false);
        bukkitPlayer.getInventory().setItemInMainHand(new ItemStack(Material.SHEARS));
        var beforeShear = probe.snapshot();
        useItemOn(gameMode, player, level, x + 2, y, z);
        probe.assertDelta("pumpkin shear", beforeShear, 0, 0, 1);
    }

    private void verifyWaterEvaporationBucketSemantics()
        throws ReflectiveOperationException {
        var world = Bukkit.getWorlds().stream()
            .filter(candidate -> candidate.getEnvironment() == World.Environment.NETHER)
            .findFirst()
            .orElseThrow(() -> new AssertionError("Nether world was not loaded."));
        var server = invoke(this.getServer(), "getServer");
        var level = invoke(world, "getHandle");
        var player = newServerPlayer(server, level);
        var bukkitPlayer = (Player) invoke(player, "getBukkitEntity");
        var gameMode = field(player, "gameMode");

        int x = 160;
        int y = Math.max(world.getMinHeight() + 8, 80);
        int z = 160;
        invoke(player, "setPos", x + 0.5, (double) y, z + 0.5);

        var clicked = world.getBlockAt(x, y, z);
        clicked.setType(Material.NETHERRACK, false);
        var target = world.getBlockAt(x, y + 1, z);
        target.setType(Material.AIR, false);

        var probe = new WaterEvaporationProbe(x, y + 1, z);
        this.getServer().getPluginManager().registerEvents(probe, this);

        bukkitPlayer.getInventory().setItemInMainHand(new ItemStack(Material.WATER_BUCKET));
        useItemOn(gameMode, player, level, x, y, z);

        probe.assertObserved();
        if (target.getType() != Material.AIR) {
            throw new AssertionError(
                "Water bucket mutated the target block in a WATER_EVAPORATES environment: "
                    + target.getType()
            );
        }
    }

    private static Object newServerPlayer(Object server, Object level)
        throws ReflectiveOperationException {
        var profileClass = Class.forName("com.mojang.authlib.GameProfile");
        var profile = construct(
            profileClass,
            UUID.fromString("123e4567-e89b-12d3-a456-426614174001"),
            "KansokushaFixture"
        );
        var clientInformationClass =
            Class.forName("net.minecraft.server.level.ClientInformation");
        var clientInformation = clientInformationClass
            .getMethod("createDefault")
            .invoke(null);
        return construct(
            Class.forName("net.minecraft.server.level.ServerPlayer"),
            server,
            level,
            profile,
            clientInformation
        );
    }

    private static void useItemOn(
        Object gameMode,
        Object player,
        Object level,
        int x,
        int y,
        int z
    ) throws ReflectiveOperationException {
        var mainHand = Class.forName("net.minecraft.world.InteractionHand")
            .getField("MAIN_HAND")
            .get(null);
        var item = invoke(player, "getMainHandItem");
        var pos = blockPos(x, y, z);
        var hitResult = blockHitResult(pos, x, y, z);
        invoke(gameMode, "useItemOn", player, level, item, mainHand, hitResult);
    }

    private static Object blockPos(int x, int y, int z)
        throws ReflectiveOperationException {
        return construct(Class.forName("net.minecraft.core.BlockPos"), x, y, z);
    }

    private static Object blockHitResult(Object pos, int x, int y, int z)
        throws ReflectiveOperationException {
        var vec3 = construct(
            Class.forName("net.minecraft.world.phys.Vec3"),
            x + 0.5,
            y + 0.5,
            z + 0.5
        );
        var up = Class.forName("net.minecraft.core.Direction")
            .getField("UP")
            .get(null);
        return construct(
            Class.forName("net.minecraft.world.phys.BlockHitResult"),
            vec3,
            up,
            pos,
            false
        );
    }

    private static Object field(Object target, String name)
        throws ReflectiveOperationException {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object invoke(Object target, String name, Object... args)
        throws ReflectiveOperationException {
        Method method = Arrays.stream(target.getClass().getMethods())
            .filter(candidate -> candidate.getName().equals(name))
            .filter(candidate -> matches(candidate.getParameterTypes(), args))
            .findFirst()
            .orElseThrow(() -> new NoSuchMethodException(
                target.getClass().getName() + "#" + name
            ));
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static Object construct(Class<?> type, Object... args)
        throws ReflectiveOperationException {
        Constructor<?> constructor = Arrays.stream(type.getConstructors())
            .filter(candidate -> matches(candidate.getParameterTypes(), args))
            .findFirst()
            .orElseThrow(() -> new NoSuchMethodException(
                "No matching constructor for " + type.getName()
            ));
        constructor.setAccessible(true);
        return constructor.newInstance(args);
    }

    private static boolean matches(Class<?>[] parameterTypes, Object[] args) {
        if (parameterTypes.length != args.length) {
            return false;
        }
        for (int i = 0; i < parameterTypes.length; i++) {
            if (!isAssignable(parameterTypes[i], args[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAssignable(Class<?> parameterType, Object arg) {
        if (arg == null) {
            return !parameterType.isPrimitive();
        }
        if (!parameterType.isPrimitive()) {
            return parameterType.isAssignableFrom(arg.getClass());
        }
        return switch (parameterType.getName()) {
            case "boolean" -> arg instanceof Boolean;
            case "byte" -> arg instanceof Byte;
            case "short" -> arg instanceof Short;
            case "int" -> arg instanceof Integer;
            case "long" -> arg instanceof Long;
            case "float" -> arg instanceof Float;
            case "double" -> arg instanceof Double;
            case "char" -> arg instanceof Character;
            default -> false;
        };
    }

    private void verifyAfterShutdown(Result result) {
        try {
            try {
                Kansokusha.api();
                throw new AssertionError("Kansokusha.api() remained available after shutdown.");
            } catch (IllegalStateException expected) {
            }

            if (result.api().submit(result.submission()) != SubmissionOutcome.CLOSED) {
                throw new AssertionError("Previously acquired API did not return CLOSED after shutdown.");
            }
            if (result.api().registerEventType(result.definition()) != RegistrationOutcome.CLOSED) {
                throw new AssertionError("Previously acquired API registration did not return CLOSED after shutdown.");
            }

            Files.writeString(this.resultFile, "success");
        } catch (Throwable failure) {
            writeFailure(failure);
        }
    }

    private void writeFailure(Throwable failure) {
        try {
            Files.createDirectories(this.resultFile.getParent());
            var stackTrace = new StringWriter();
            failure.printStackTrace(new PrintWriter(stackTrace));
            Files.writeString(this.resultFile, "failure\n" + stackTrace);
        } catch (Exception writeFailure) {
            failure.addSuppressed(writeFailure);
            failure.printStackTrace();
        }
    }

    private record Result(
        KansokushaApi api,
        net.okocraft.kansokusha.api.event.EventTypeDefinition definition,
        EventSubmission submission
    ) {
    }

    private static final class WaterEvaporationProbe implements Listener {

        private final int x;
        private final int y;
        private final int z;
        private boolean observed;
        private Throwable failure;

        private WaterEvaporationProbe(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onBucketEmpty(PlayerBucketEmptyEvent event) {
            if (
                event.getBlock().getX() != this.x
                    || event.getBlock().getY() != this.y
                    || event.getBlock().getZ() != this.z
            ) {
                return;
            }

            this.observed = true;
            try {
                var kansokushaListener = Arrays.stream(
                    PlayerBucketEmptyEvent.getHandlerList().getRegisteredListeners()
                )
                    .filter(listener -> listener.getPlugin().getName().equals("Kansokusha"))
                    .map(listener -> listener.getListener())
                    .filter(listener -> listener.getClass().getName().equals(
                        "net.okocraft.kansokusha.paper.builtin.PaperBucketListener"
                    ))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                        "Kansokusha bucket listener was not registered."
                    ));
                var inFlight = (java.util.Map<?, ?>) field(kansokushaListener, "inFlight");
                var snapshot = inFlight.get(event);
                if (snapshot == null) {
                    throw new AssertionError(
                        "Kansokusha did not capture the bucket event at LOWEST."
                    );
                }

                var preState = (EventPayload) invoke(snapshot, "preState");
                var expectedPostState = (EventPayload) invoke(snapshot, "expectedPostState");
                if (!Arrays.equals(preState.copyBytes(), expectedPostState.copyBytes())) {
                    throw new AssertionError(
                        "WATER_EVAPORATES bucket expected_post_state did not preserve pre_state."
                    );
                }
            } catch (Throwable failure) {
                this.failure = failure;
            }
        }

        private void assertObserved() {
            if (!this.observed) {
                throw new AssertionError(
                    "Water bucket did not emit PlayerBucketEmptyEvent in the Nether fixture."
                );
            }
            if (this.failure != null) {
                throw new AssertionError(
                    "Water evaporation bucket snapshot verification failed.",
                    this.failure
                );
            }
        }
    }

    private static final class PlatformEventProbe implements Listener {

        private int blockBreak;
        private int harvest;
        private int shear;

        @EventHandler
        public void onBlockBreak(BlockBreakEvent event) {
            this.blockBreak++;
        }

        @EventHandler
        public void onHarvest(PlayerHarvestBlockEvent event) {
            this.harvest++;
        }

        @EventHandler
        public void onShear(PlayerShearBlockEvent event) {
            this.shear++;
        }

        Snapshot snapshot() {
            return new Snapshot(this.blockBreak, this.harvest, this.shear);
        }

        void assertDelta(
            String action,
            Snapshot before,
            int expectedBlockBreak,
            int expectedHarvest,
            int expectedShear
        ) {
            var actualBlockBreak = this.blockBreak - before.blockBreak();
            var actualHarvest = this.harvest - before.harvest();
            var actualShear = this.shear - before.shear();
            if (
                actualBlockBreak != expectedBlockBreak
                    || actualHarvest != expectedHarvest
                    || actualShear != expectedShear
            ) {
                throw new AssertionError(
                    action
                        + " emitted unexpected platform events: blockBreak="
                        + actualBlockBreak
                        + ", harvest="
                        + actualHarvest
                        + ", shear="
                        + actualShear
                );
            }
        }

        private record Snapshot(int blockBreak, int harvest, int shear) {
        }
    }
}
