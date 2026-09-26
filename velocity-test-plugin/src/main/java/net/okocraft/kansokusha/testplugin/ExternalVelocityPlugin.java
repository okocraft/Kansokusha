package net.okocraft.kansokusha.testplugin;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.Kansokusha;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.event.PayloadGeneration;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Submits one event through the public API and verifies the API is closed during shutdown.
 * The Gradle task then checks that the event was persisted.
 */
public final class ExternalVelocityPlugin {

    private static final Key EVENT_TYPE = Key.key("fixture", "custom_event");
    private static final Key BACKEND_SERVER = Key.key("fixture", "backend");

    private final ProxyServer proxy;
    private final Path resultFile;

    private KansokushaApi api;
    private EventSubmission submission;
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
            this.api = Kansokusha.api();
            check(this.api.localServerKey().isEmpty(), "Velocity API unexpectedly exposes a local server identity.");
            this.api.registerEventType(new EventTypeDefinition(EVENT_TYPE, PayloadGeneration.FIRST));

            this.submission = new EventSubmission(
                EVENT_TYPE,
                PayloadGeneration.FIRST,
                Instant.now(),
                BACKEND_SERVER,
                null,
                null,
                null,
                EventPayload.copyOf(new byte[]{1, 2, 3})
            );
            check(this.api.submit(this.submission), "External event submission was not accepted.");
        } catch (Throwable failure) {
            this.writeFailure(failure);
        }

        this.proxy.getScheduler().buildTask(this, this.proxy::shutdown).schedule();
    }

    // Kansokusha handles ProxyShutdownEvent with the highest priority, so it has already shut down here.
    @Subscribe(priority = Short.MIN_VALUE)
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (this.failed) {
            return;
        }

        try {
            try {
                Kansokusha.api();
                throw new AssertionError("Kansokusha.api() remained available during shutdown.");
            } catch (IllegalStateException expected) {
            }
            check(!this.api.submit(this.submission), "Previously acquired API accepted an event after shutdown.");

            Files.createDirectories(this.resultFile.getParent());
            Files.writeString(this.resultFile, "success");
        } catch (Throwable failure) {
            this.writeFailure(failure);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
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
}
