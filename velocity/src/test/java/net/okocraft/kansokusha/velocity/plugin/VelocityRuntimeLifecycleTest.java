package net.okocraft.kansokusha.velocity.plugin;

import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.common.reporting.AdministratorReporter;
import net.okocraft.kansokusha.common.runtime.KansokushaRuntime;
import net.okocraft.kansokusha.common.storage.DuckDbDatabase;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

class VelocityRuntimeLifecycleTest {

    @Test
    void testRealRuntimeUsesIndependentProxyStorageWithoutLocalServerIdentity(@TempDir Path dir)
        throws Exception {
        writeConfig(dir);
        var reporter = Mockito.mock(AdministratorReporter.class);
        var lifecycle = new VelocityRuntimeLifecycle(dir, reporter);

        lifecycle.start();

        var runtime = lifecycle.runtime();
        Assertions.assertNotNull(runtime);
        Assertions.assertTrue(runtime.api().localServerKey().isEmpty());
        var databasePath = dir.resolve("kansokusha.duckdb");
        Assertions.assertTrue(Files.isRegularFile(databasePath));

        lifecycle.close();
        Assertions.assertNull(lifecycle.runtime());
        Mockito.verifyNoInteractions(reporter);

        try (var reopened = DuckDbDatabase.open(databasePath)) {
            Assertions.assertNotNull(reopened);
        }
    }

    @Test
    void testStartPublishesApiAndCloseUnpublishesBeforeRuntimeStop(@TempDir Path dir)
        throws Exception {
        var runtime = Mockito.mock(KansokushaRuntime.class);
        var api = Mockito.mock(KansokushaApi.class);
        Mockito.when(runtime.api()).thenReturn(api);
        var reporter = Mockito.mock(AdministratorReporter.class);
        var publication = Mockito.mock(VelocityRuntimeLifecycle.ApiPublication.class);
        var lifecycle = new VelocityRuntimeLifecycle(
            dir,
            reporter,
            (dataDirectory, actualReporter) -> {
                Assertions.assertEquals(dir, dataDirectory);
                Assertions.assertSame(reporter, actualReporter);
                return runtime;
            },
            publication
        );

        lifecycle.start();

        Assertions.assertSame(runtime, lifecycle.runtime());
        Mockito.verify(publication).publish(api);

        lifecycle.close();

        Assertions.assertNull(lifecycle.runtime());
        var order = Mockito.inOrder(publication, runtime);
        order.verify(publication).unpublish(api);
        order.verify(runtime).close();

        lifecycle.close();
        Mockito.verify(runtime, Mockito.times(2)).api();
        Mockito.verifyNoMoreInteractions(runtime);
    }

    @Test
    void testPublicationFailureClosesStartedRuntime(@TempDir Path dir) throws Exception {
        var runtime = Mockito.mock(KansokushaRuntime.class);
        var api = Mockito.mock(KansokushaApi.class);
        Mockito.when(runtime.api()).thenReturn(api);
        var reporter = Mockito.mock(AdministratorReporter.class);
        var publication = Mockito.mock(VelocityRuntimeLifecycle.ApiPublication.class);
        var failure = new IllegalStateException("already published");
        Mockito.doThrow(failure).when(publication).publish(api);
        var lifecycle = new VelocityRuntimeLifecycle(
            dir,
            reporter,
            (dataDirectory, actualReporter) -> runtime,
            publication
        );

        Assertions.assertSame(
            failure,
            Assertions.assertThrows(IllegalStateException.class, lifecycle::start)
        );
        Mockito.verify(runtime).close();
        Assertions.assertNull(lifecycle.runtime());
    }

    @Test
    void testShutdownFailureIsReported(@TempDir Path dir) throws Exception {
        var runtime = Mockito.mock(KansokushaRuntime.class);
        var api = Mockito.mock(KansokushaApi.class);
        Mockito.when(runtime.api()).thenReturn(api);
        var reporter = Mockito.mock(AdministratorReporter.class);
        var publication = Mockito.mock(VelocityRuntimeLifecycle.ApiPublication.class);
        var failure = new SQLException("close failed");
        Mockito.doThrow(failure).when(runtime).close();
        var lifecycle = new VelocityRuntimeLifecycle(
            dir,
            reporter,
            (dataDirectory, actualReporter) -> runtime,
            publication
        );
        lifecycle.start();

        lifecycle.close();

        Mockito.verify(publication).unpublish(api);
        Mockito.verify(reporter).report(
            "Kansokusha runtime failed to shut down cleanly.",
            failure
        );
        Assertions.assertNull(lifecycle.runtime());
    }

    @Test
    void testFatalShutdownFailureIsReportedAndRethrown(@TempDir Path dir) throws Exception {
        var runtime = Mockito.mock(KansokushaRuntime.class);
        var api = Mockito.mock(KansokushaApi.class);
        Mockito.when(runtime.api()).thenReturn(api);
        var reporter = Mockito.mock(AdministratorReporter.class);
        var publication = Mockito.mock(VelocityRuntimeLifecycle.ApiPublication.class);
        var failure = new AssertionError("fatal close failure");
        Mockito.doThrow(failure).when(runtime).close();
        var lifecycle = new VelocityRuntimeLifecycle(
            dir,
            reporter,
            (dataDirectory, actualReporter) -> runtime,
            publication
        );
        lifecycle.start();

        var thrown = Assertions.assertThrows(AssertionError.class, lifecycle::close);

        Assertions.assertSame(failure, thrown);
        Mockito.verify(publication).unpublish(api);
        Mockito.verify(reporter).report(
            "Kansokusha runtime failed to shut down cleanly.",
            failure
        );
        Assertions.assertNull(lifecycle.runtime());
    }

    @Test
    void testStartupFailureDoesNotPublishOrInstallRuntime(@TempDir Path dir) {
        var reporter = Mockito.mock(AdministratorReporter.class);
        var publication = Mockito.mock(VelocityRuntimeLifecycle.ApiPublication.class);
        var failure = new SQLException("startup failed");
        var lifecycle = new VelocityRuntimeLifecycle(
            dir,
            reporter,
            (dataDirectory, actualReporter) -> {
                throw failure;
            },
            publication
        );

        Assertions.assertSame(
            failure,
            Assertions.assertThrows(SQLException.class, lifecycle::start)
        );
        Assertions.assertNull(lifecycle.runtime());
        Mockito.verifyNoInteractions(publication);
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
