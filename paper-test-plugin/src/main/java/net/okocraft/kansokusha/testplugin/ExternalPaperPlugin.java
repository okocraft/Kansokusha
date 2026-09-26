package net.okocraft.kansokusha.testplugin;

import net.kyori.adventure.text.Component;
import net.okocraft.kansokusha.api.Kansokusha;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.RegistrationOutcome;
import net.okocraft.kansokusha.api.SubmissionOutcome;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import net.okocraft.kansokusha.paper.builtin.PaperBlockBreakListener;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.mcprotocollib.auth.SessionService;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.protocol.MinecraftConstants;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ExternalPaperPlugin extends JavaPlugin implements Listener {

    private static final NamespacedKey EVENT_TYPE =
        new NamespacedKey("fixture", "custom_event");
    private static final String SESSION_PLAYER_NAME = "KansokuFixture";
    private static final Component INITIAL_KICK_REASON =
        Component.text("kansokusha session fixture");
    private static final Component FINAL_KICK_REASON =
        Component.text("kansokusha final session fixture");

    private static final Set<String> EXPECTED_BUILT_IN_LISTENERS = Set.of(
        "net.okocraft.kansokusha.paper.builtin.PaperBlockBreakListener",
        "net.okocraft.kansokusha.paper.builtin.PaperBlockPlaceListener",
        "net.okocraft.kansokusha.paper.builtin.PaperSignChangeListener",
        "net.okocraft.kansokusha.paper.builtin.PaperBucketListener",
        "net.okocraft.kansokusha.paper.builtin.PaperBlockHarvestListener",
        "net.okocraft.kansokusha.paper.builtin.PaperFlowerPotChangeListener",
        "net.okocraft.kansokusha.paper.builtin.PaperBlockIgniteListener",
        "net.okocraft.kansokusha.paper.builtin.PaperBlockBurnListener",
        "net.okocraft.kansokusha.paper.builtin.PaperTntPrimeListener",
        "net.okocraft.kansokusha.paper.builtin.PaperExplosionBlockChangeListener",
        "net.okocraft.kansokusha.paper.builtin.PaperPistonMoveListener",
        "net.okocraft.kansokusha.paper.builtin.PaperEntityBlockChangeListener",
        "net.okocraft.kansokusha.paper.builtin.PaperNaturalBlockChangeListener",
        "net.okocraft.kansokusha.paper.builtin.PaperFluidChangeListener",
        "net.okocraft.kansokusha.paper.builtin.PaperSpongeAbsorbListener",
        "net.okocraft.kansokusha.paper.builtin.PaperBlockFertilizeListener",
        "net.okocraft.kansokusha.paper.builtin.PaperCauldronLevelChangeListener",
        "net.okocraft.kansokusha.paper.builtin.PaperContainerTransferListener",
        "net.okocraft.kansokusha.paper.builtin.PaperContainerPickupListener",
        "net.okocraft.kansokusha.paper.builtin.PaperContainerProcessListener",
        "net.okocraft.kansokusha.paper.builtin.PaperPlayerJoinListener",
        "net.okocraft.kansokusha.paper.builtin.PaperPlayerQuitListener",
        "net.okocraft.kansokusha.paper.builtin.PaperPlayerKickListener",
        "net.okocraft.kansokusha.paper.builtin.PaperPlayerWorldChangeListener",
        "net.okocraft.kansokusha.paper.builtin.PaperPlayerTeleportListener",
        "net.okocraft.kansokusha.paper.builtin.PaperPlayerGameModeChangeListener",
        "net.okocraft.kansokusha.paper.builtin.PaperPlayerSpawnChangeListener",
        "net.okocraft.kansokusha.paper.builtin.PaperPlayerDeathListener",
        "net.okocraft.kansokusha.paper.builtin.PaperChatListener",
        "net.okocraft.kansokusha.paper.builtin.PaperPlayerCommandListener",
        "net.okocraft.kansokusha.paper.builtin.PaperServerCommandListener",
        "net.okocraft.kansokusha.paper.builtin.PaperEntityPlaceListener",
        "net.okocraft.kansokusha.paper.builtin.PaperEntityBreakListener",
        "net.okocraft.kansokusha.paper.builtin.PaperEntityStateChangeListener",
        "net.okocraft.kansokusha.paper.builtin.PaperGameRuleChangeListener",
        "net.okocraft.kansokusha.paper.builtin.PaperWorldDifficultyChangeListener",
        "net.okocraft.kansokusha.paper.builtin.PaperWorldBorderChangeListener",
        "net.okocraft.kansokusha.paper.builtin.PaperWorldSpawnChangeListener",
        "net.okocraft.kansokusha.paper.builtin.PaperWhitelistChangeListener"
    );

    private final List<String> sessionEvents = new ArrayList<>();
    private volatile boolean sessionSemanticsVerified;
    private volatile Throwable failure;
    private volatile Result result;
    private PaperBlockBreakListener pendingBlockBreakListener;
    private Path resultFile;

    @Override
    public void onEnable() {
        this.resultFile = Path.of(System.getProperty("kansokusha.external-api-fixture.result"));

        try {
            var result = registerAndSubmit();
            this.result = result;
            verifyBuiltInListenerWiring();
            Bukkit.getPluginManager().registerEvents(this, this);
            Runtime.getRuntime().addShutdownHook(
                new Thread(() -> verifyAfterShutdown(result), "kansokusha-external-api-fixture")
            );
            Bukkit.getScheduler().runTaskLater(this, this::startSessionSemanticsClient, 1L);
            Bukkit.getScheduler().runTaskLater(
                this,
                () -> {
                    if (!this.sessionSemanticsVerified && this.failure == null) {
                        failAndShutdown(
                            new AssertionError(
                                "Paper 26.2 session semantics fixture timed out before observing join -> kick -> quit."
                            )
                        );
                    }
                },
                600L
            );
        } catch (Throwable failure) {
            failAndShutdown(failure);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void observeJoin(PlayerJoinEvent event) {
        if (!isSessionFixture(event.getPlayer().getName())) {
            return;
        }

        try {
            assertEventOrder(List.of(), "join");
            this.sessionEvents.add("join");
            Bukkit.getScheduler().runTask(
                this,
                () -> event.getPlayer().kick(INITIAL_KICK_REASON, PlayerKickEvent.Cause.PLUGIN)
            );
        } catch (Throwable failure) {
            failAndShutdown(failure);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void replaceKickReason(PlayerKickEvent event) {
        if (isSessionFixture(event.getPlayer().getName())) {
            event.reason(FINAL_KICK_REASON);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void observeKick(PlayerKickEvent event) {
        if (!isSessionFixture(event.getPlayer().getName())) {
            return;
        }

        try {
            if (event.isCancelled()) {
                throw new AssertionError("Paper session fixture kick was unexpectedly cancelled.");
            }
            if (event.getCause() != PlayerKickEvent.Cause.PLUGIN) {
                throw new AssertionError("Unexpected kick cause: " + event.getCause());
            }
            if (!event.reason().equals(FINAL_KICK_REASON)) {
                throw new AssertionError("MONITOR did not observe the final kick reason.");
            }
            assertEventOrder(List.of("join"), "kick");
            this.sessionEvents.add("kick");
        } catch (Throwable failure) {
            failAndShutdown(failure);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void observeQuit(PlayerQuitEvent event) {
        if (!isSessionFixture(event.getPlayer().getName())) {
            return;
        }

        try {
            if (event.getReason() != PlayerQuitEvent.QuitReason.KICKED) {
                throw new AssertionError(
                    "Accepted Paper 26.2 kick produced quit reason " + event.getReason()
                        + " instead of KICKED."
                );
            }
            assertEventOrder(List.of("join", "kick"), "quit");
            this.sessionEvents.add("quit");
            capturePendingBlockBreak(event.getPlayer());
            this.sessionSemanticsVerified = true;
            Bukkit.getScheduler().runTask(this, this::verifyDisableLifecycleAndShutdown);
        } catch (Throwable failure) {
            failAndShutdown(failure);
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
        var registered = registeredKansokushaListenerClasses();
        if (!registered.equals(EXPECTED_BUILT_IN_LISTENERS)) {
            throw new AssertionError(
                "Unexpected Kansokusha built-in listener set. expected="
                    + EXPECTED_BUILT_IN_LISTENERS + ", actual=" + registered
            );
        }
        if (
            registered.contains(
                "net.okocraft.kansokusha.paper.builtin.PaperPlayerItemAuditListener"
            )
        ) {
            throw new AssertionError(
                "Delegated PaperPlayerItemAuditListener must not be registered directly."
            );
        }
    }

    private void capturePendingBlockBreak(Player player) {
        var listener = findRegisteredBlockBreakListener();
        if (inFlightCount(listener) != 0) {
            throw new AssertionError("Block-break listener already had in-flight state.");
        }

        listener.capture(new BlockBreakEvent(player.getLocation().getBlock(), player));
        if (inFlightCount(listener) != 1) {
            throw new AssertionError("Could not stage a block-break snapshot before disable.");
        }
        this.pendingBlockBreakListener = listener;
    }

    private void verifyDisableLifecycleAndShutdown() {
        try {
            var result = this.result;
            if (result == null) {
                throw new AssertionError("External API fixture result was not initialized.");
            }
            var listener = this.pendingBlockBreakListener;
            if (listener == null) {
                throw new AssertionError("Pending block-break listener was not captured.");
            }

            var kansokusha = Bukkit.getPluginManager().getPlugin("Kansokusha");
            if (kansokusha == null || !kansokusha.isEnabled()) {
                throw new AssertionError("Kansokusha was not enabled before lifecycle verification.");
            }

            Bukkit.getPluginManager().disablePlugin(kansokusha);

            if (kansokusha.isEnabled()) {
                throw new AssertionError("Kansokusha remained enabled after disablePlugin.");
            }
            var remainingListeners = registeredKansokushaListenerClasses();
            if (!remainingListeners.isEmpty()) {
                throw new AssertionError(
                    "Kansokusha listeners remained registered after disable: " + remainingListeners
                );
            }
            if (inFlightCount(listener) != 0) {
                throw new AssertionError(
                    "Kansokusha did not clear LOWEST-to-MONITOR state before runtime shutdown."
                );
            }
            assertApiClosed(result, "disable");

            Bukkit.shutdown();
        } catch (Throwable failure) {
            failAndShutdown(failure);
        }
    }

    private void startSessionSemanticsClient() {
        var port = Bukkit.getPort();
        Thread.ofPlatform()
            .daemon()
            .name("kansokusha-paper-26.2-session-client")
            .start(() -> {
                try {
                    var protocol = new MinecraftProtocol(SESSION_PLAYER_NAME);
                    var client = ClientNetworkSessionFactory.factory()
                        .setRemoteSocketAddress(new InetSocketAddress("127.0.0.1", port))
                        .setProtocol(protocol)
                        .create();
                    client.setFlag(MinecraftConstants.SESSION_SERVICE_KEY, new SessionService());
                    client.connect();
                } catch (Throwable failure) {
                    failAndShutdown(failure);
                }
            });
    }

    private void assertEventOrder(List<String> expectedBefore, String next) {
        if (!this.sessionEvents.equals(expectedBefore)) {
            throw new AssertionError(
                "Unexpected Paper 26.2 session event order before " + next + ": " + this.sessionEvents
            );
        }
    }

    private static boolean isSessionFixture(String playerName) {
        return SESSION_PLAYER_NAME.equals(playerName);
    }

    private static Set<String> registeredKansokushaListenerClasses() {
        var result = new HashSet<String>();
        for (var handlers : HandlerList.getHandlerLists()) {
            for (var registered : handlers.getRegisteredListeners()) {
                if (registered.getPlugin().getName().equals("Kansokusha")) {
                    result.add(registered.getListener().getClass().getName());
                }
            }
        }
        return Set.copyOf(result);
    }

    private static PaperBlockBreakListener findRegisteredBlockBreakListener() {
        for (var registered : BlockBreakEvent.getHandlerList().getRegisteredListeners()) {
            if (
                registered.getPlugin().getName().equals("Kansokusha")
                    && registered.getListener() instanceof PaperBlockBreakListener listener
            ) {
                return listener;
            }
        }
        throw new AssertionError("PaperBlockBreakListener was not registered.");
    }

    private static int inFlightCount(PaperBlockBreakListener listener) {
        try {
            var method = PaperBlockBreakListener.class.getDeclaredMethod("inFlightCount");
            method.setAccessible(true);
            return (int) method.invoke(listener);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not inspect block-break in-flight state.", failure);
        }
    }

    private void verifyAfterShutdown(Result result) {
        if (this.failure != null) {
            return;
        }

        try {
            if (!this.sessionSemanticsVerified) {
                throw new AssertionError(
                    "Paper 26.2 session semantics were not verified before shutdown."
                );
            }
            if (!this.sessionEvents.equals(List.of("join", "kick", "quit"))) {
                throw new AssertionError(
                    "Unexpected Paper 26.2 session event sequence: " + this.sessionEvents
                );
            }

            assertApiClosed(result, "shutdown");
            Files.writeString(this.resultFile, "success");
        } catch (Throwable failure) {
            writeFailure(failure);
        }
    }

    private static void assertApiClosed(Result result, String phase) {
        try {
            Kansokusha.api();
            throw new AssertionError("Kansokusha.api() remained available after " + phase + ".");
        } catch (IllegalStateException expected) {
        }

        if (result.api().submit(result.submission()) != SubmissionOutcome.CLOSED) {
            throw new AssertionError(
                "Previously acquired API did not return CLOSED after " + phase + "."
            );
        }
        if (result.api().registerEventType(result.definition()) != RegistrationOutcome.CLOSED) {
            throw new AssertionError(
                "Previously acquired API registration did not return CLOSED after " + phase + "."
            );
        }
    }

    private void failAndShutdown(Throwable failure) {
        if (this.failure != null) {
            return;
        }

        this.failure = failure;
        writeFailure(failure);
        try {
            Bukkit.getScheduler().runTask(this, Bukkit::shutdown);
        } catch (RuntimeException schedulingFailure) {
            failure.addSuppressed(schedulingFailure);
            Bukkit.shutdown();
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
}
