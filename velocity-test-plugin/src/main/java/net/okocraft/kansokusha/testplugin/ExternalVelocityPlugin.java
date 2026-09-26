package net.okocraft.kansokusha.testplugin;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.event.proxy.server.ServerRegisteredEvent;
import com.velocitypowered.api.event.proxy.server.ServerUnregisteredEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.Kansokusha;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.RegistrationOutcome;
import net.okocraft.kansokusha.api.SubmissionOutcome;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.event.PayloadGeneration;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ExternalVelocityPlugin {

    private static final Key EVENT_TYPE = Key.key("fixture", "custom_event");
    private static final Key BACKEND_SERVER = Key.key("fixture", "backend");

    private final ProxyServer proxy;
    private final Path resultFile;
    private final List<RegistryEvent> registryEvents = new CopyOnWriteArrayList<>();

    private Result result;
    private volatile boolean failed;

    @Inject
    public ExternalVelocityPlugin(ProxyServer proxy) {
        this.proxy = proxy;
        this.resultFile = Path.of(
            System.getProperty("kansokusha.external-velocity-api-fixture.result")
        );
    }

    @Subscribe(priority = Short.MIN_VALUE)
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            if (!this.registryEvents.isEmpty()) {
                throw new AssertionError(
                    "Static backend population fired registry events after plugin listeners were registered: "
                        + this.registryEvents
                );
            }
            if (this.proxy.getServer("lobby").isEmpty()) {
                throw new AssertionError(
                    "Velocity static backend population was not complete before ProxyInitializeEvent."
                );
            }
            this.result = registerAndSubmit();
        } catch (Throwable failure) {
            writeFailure(failure);
            this.proxy.getScheduler().buildTask(this, this.proxy::shutdown).schedule();
            return;
        }

        this.proxy.getScheduler().buildTask(this, () -> {
            try {
                verifyBackendRegistryLifecycle();
            } catch (Throwable failure) {
                writeFailure(failure);
            } finally {
                this.proxy.shutdown();
            }
        }).schedule();
    }

    @Subscribe(async = false)
    public void onServerRegistered(ServerRegisteredEvent event) {
        this.registryEvents.add(RegistryEvent.snapshot("register", event.registeredServer()));
    }

    @Subscribe(async = false)
    public void onServerUnregistered(ServerUnregisteredEvent event) {
        this.registryEvents.add(RegistryEvent.snapshot("unregister", event.unregisteredServer()));
    }

    @Subscribe(priority = Short.MIN_VALUE)
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (this.failed) {
            return;
        }

        var current = this.result;
        if (current == null) {
            return;
        }

        try {
            try {
                Kansokusha.api();
                throw new AssertionError("Kansokusha.api() remained available during shutdown.");
            } catch (IllegalStateException expected) {
            }

            if (current.api().submit(current.submission()) != SubmissionOutcome.CLOSED) {
                throw new AssertionError("Previously acquired API did not return CLOSED after shutdown.");
            }
            if (current.api().registerEventType(current.definition()) != RegistrationOutcome.CLOSED) {
                throw new AssertionError(
                    "Previously acquired API registration did not return CLOSED after shutdown."
                );
            }

            Files.createDirectories(this.resultFile.getParent());
            Files.writeString(this.resultFile, "success");
        } catch (Throwable failure) {
            writeFailure(failure);
        }
    }

    private void verifyBackendRegistryLifecycle() throws Exception {
        var runtime = new ServerInfo(
            "fixture-runtime",
            InetSocketAddress.createUnresolved("runtime.invalid", 25577)
        );
        this.proxy.registerServer(runtime);
        this.proxy.unregisterServer(runtime);

        var lobby = this.proxy.getServer("lobby").orElseThrow(
            () -> new AssertionError("Static lobby backend disappeared before reload test.")
        );
        var previousLobbyPort = lobby.getServerInfo().getAddress().getPort();
        var replacementLobbyPort = previousLobbyPort == 30166 ? 30167 : 30166;

        var velocityConfig = Path.of("velocity.toml");
        var config = Files.readString(velocityConfig);
        Files.writeString(
            velocityConfig,
            replaceServerAddress(
                config,
                "lobby",
                "127.0.0.1:" + replacementLobbyPort
            )
        );

        var executed = this.proxy.getCommandManager()
            .executeAsync(this.proxy.getConsoleCommandSource(), "velocity reload")
            .join();
        if (!executed) {
            throw new AssertionError("Velocity reload command was not executed.");
        }

        var events = List.copyOf(this.registryEvents);
        if (events.size() != 4) {
            throw new AssertionError(
                "Expected runtime register/unregister and reload unregister/register only, got "
                    + events
            );
        }

        assertRegistryEvent(events.get(0), "register", "fixture-runtime", 25577);
        assertRegistryEvent(events.get(1), "unregister", "fixture-runtime", 25577);
        assertRegistryEvent(events.get(2), "unregister", "lobby", previousLobbyPort);
        assertRegistryEvent(events.get(3), "register", "lobby", replacementLobbyPort);
    }

    private static String replaceServerAddress(
        String config,
        String serverName,
        String address
    ) {
        var prefix = serverName + " = \"";
        var start = config.indexOf(prefix);
        if (start < 0) {
            throw new AssertionError(
                "Could not find backend '" + serverName + "' in generated velocity.toml."
            );
        }
        var valueStart = start + prefix.length();
        var valueEnd = config.indexOf('"', valueStart);
        if (valueEnd < 0) {
            throw new AssertionError(
                "Could not find closing address quote for backend '" + serverName + "'."
            );
        }
        return config.substring(0, valueStart)
            + address
            + config.substring(valueEnd);
    }

    private static void assertRegistryEvent(
        RegistryEvent event,
        String action,
        String serverName,
        int port
    ) {
        if (
            !event.action().equals(action)
                || !event.serverName().equals(serverName)
                || event.port() != port
        ) {
            throw new AssertionError(
                "Unexpected backend registry event: expected "
                    + action
                    + " "
                    + serverName
                    + ":"
                    + port
                    + ", got "
                    + event
            );
        }
    }

    private Result registerAndSubmit() {
        var api = Kansokusha.api();
        if (api.localServerKey().isPresent()) {
            throw new AssertionError("Velocity API unexpectedly exposes a local server identity.");
        }

        var definition = new EventTypeDefinition(EVENT_TYPE, PayloadGeneration.FIRST);
        if (api.registerEventType(definition) != RegistrationOutcome.REGISTERED) {
            throw new AssertionError("External event type registration did not succeed.");
        }

        var submission = new EventSubmission(
            EVENT_TYPE,
            PayloadGeneration.FIRST,
            Instant.parse("2026-09-21T00:00:00Z"),
            BACKEND_SERVER,
            null,
            null,
            null,
            EventPayload.copyOf(new byte[]{1, 2, 3})
        );

        if (api.submit(submission) != SubmissionOutcome.ACCEPTED) {
            throw new AssertionError("External event submission was not accepted.");
        }

        return new Result(api, definition, submission);
    }

    private void writeFailure(Throwable failure) {
        this.failed = true;
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

    private record RegistryEvent(String action, String serverName, String host, int port) {

        static RegistryEvent snapshot(String action, RegisteredServer server) {
            var info = server.getServerInfo();
            return new RegistryEvent(
                action,
                info.getName(),
                info.getAddress().getHostString(),
                info.getAddress().getPort()
            );
        }
    }

    private record Result(
        KansokushaApi api,
        EventTypeDefinition definition,
        EventSubmission submission
    ) {
    }
}
