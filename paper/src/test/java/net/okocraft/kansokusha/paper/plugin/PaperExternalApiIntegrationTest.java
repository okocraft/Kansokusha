package net.okocraft.kansokusha.paper.plugin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.Kansokusha;
import net.okocraft.kansokusha.api.RegistrationOutcome;
import net.okocraft.kansokusha.api.SubmissionOutcome;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import net.okocraft.kansokusha.paper.fixture.ExternalPaperPluginFixture;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;

class PaperExternalApiIntegrationTest {

    private static final Key SERVER_KEY = Key.key("example", "paper");

    @Test
    void testExternalPluginCanDiscoverRegisterAndSubmit(@TempDir Path dir) throws Exception {
        writeConfig(dir);
        var failures = new CopyOnWriteArrayList<Throwable>();
        var lifecycle = new PaperRuntimeLifecycle(
            dir,
            SERVER_KEY,
            (message, failure) -> failures.add(failure)
        );

        Assertions.assertThrows(IllegalStateException.class, Kansokusha::api);

        ExternalPaperPluginFixture.Result result;
        lifecycle.start();
        try {
            result = ExternalPaperPluginFixture.registerAndSubmit();

            Assertions.assertEquals(RegistrationOutcome.REGISTERED, result.registration());
            Assertions.assertEquals(SubmissionOutcome.ACCEPTED, result.submissionOutcome());
            Assertions.assertEquals(SERVER_KEY, result.api().localServerKey().orElseThrow());
            Assertions.assertEquals(
                ExternalPaperPluginFixture.EVENT_TYPE,
                PaperKansokusha.namespacedKey(result.submission().eventType())
            );
        } finally {
            lifecycle.close();
        }

        Assertions.assertTrue(failures.isEmpty(), failures::toString);
        Assertions.assertThrows(IllegalStateException.class, Kansokusha::api);
        Assertions.assertEquals(
            SubmissionOutcome.CLOSED,
            result.api().submit(result.submission())
        );
        Assertions.assertEquals(
            RegistrationOutcome.CLOSED,
            result.api().registerEventType(
                PaperKansokusha.eventType(
                    ExternalPaperPluginFixture.EVENT_TYPE,
                    PayloadGeneration.FIRST
                )
            )
        );
    }

    private static void writeConfig(Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(
            dir.resolve("config.yml"),
            """
                ingestion:
                  queue-capacity: 4
                  max-batch-size: 4
                  max-batch-delay: PT1H
                retention:
                  policies:
                    - key: example:default
                      duration: P1D
                  fallback-policy: example:default
                  cleanup-interval: PT1H
                  max-rows-per-pass: 100
                """
        );
    }
}
