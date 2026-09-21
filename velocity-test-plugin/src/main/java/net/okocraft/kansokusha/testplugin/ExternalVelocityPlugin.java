package net.okocraft.kansokusha.testplugin;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.proxy.ProxyServer;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

public final class ExternalVelocityPlugin {

    private static final Key EVENT_TYPE = Key.key("fixture", "custom_event");
    private static final Key BACKEND_SERVER = Key.key("fixture", "backend");

    private final ProxyServer proxy;
    private final Path resultFile;

    private Result result;

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
            this.result = registerAndSubmit();
        } catch (Throwable failure) {
            writeFailure(failure);
        } finally {
            this.proxy.getScheduler().buildTask(this, () -> this.proxy.shutdown()).schedule();
        }
    }

    @Subscribe(priority = Short.MIN_VALUE)
    public void onProxyShutdown(ProxyShutdownEvent event) {
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
        EventTypeDefinition definition,
        EventSubmission submission
    ) {
    }
}
