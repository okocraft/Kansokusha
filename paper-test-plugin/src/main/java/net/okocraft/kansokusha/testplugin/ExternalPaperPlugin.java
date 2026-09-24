package net.okocraft.kansokusha.testplugin;

import io.papermc.paper.event.block.PlayerShearBlockEvent;
import io.papermc.paper.event.player.PlayerFlowerPotManipulateEvent;
import net.kyori.adventure.text.Component;
import net.okocraft.kansokusha.api.Kansokusha;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.RegistrationOutcome;
import net.okocraft.kansokusha.api.SubmissionOutcome;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
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
import java.util.Arrays;
import java.util.List;

public final class ExternalPaperPlugin extends JavaPlugin implements Listener {

    private static final NamespacedKey EVENT_TYPE =
        new NamespacedKey("fixture", "custom_event");
    private static final String SESSION_PLAYER_NAME = "KansokuFixture";
    private static final Component INITIAL_KICK_REASON =
        Component.text("kansokusha session fixture");
    private static final Component FINAL_KICK_REASON =
        Component.text("kansokusha final session fixture");

    private final List<String> sessionEvents = new ArrayList<>();
    private volatile boolean sessionSemanticsVerified;
    private volatile Throwable failure;
    private Path resultFile;

    @Override
    public void onEnable() {
        this.resultFile = Path.of(System.getProperty("kansokusha.external-api-fixture.result"));
        Bukkit.getOfflinePlayer(SESSION_PLAYER_NAME).setWhitelisted(true);

        final Result result;
        try {
            result = registerAndSubmit();
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
            this.sessionSemanticsVerified = true;
            Bukkit.getScheduler().runTask(this, Bukkit::shutdown);
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
