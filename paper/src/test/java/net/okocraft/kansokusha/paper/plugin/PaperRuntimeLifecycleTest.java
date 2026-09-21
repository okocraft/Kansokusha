package net.okocraft.kansokusha.paper.plugin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.common.reporting.AdministratorReporter;
import net.okocraft.kansokusha.common.runtime.KansokushaRuntime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.sql.SQLException;

class PaperRuntimeLifecycleTest {

    private static final Key SERVER_KEY = Key.key("example", "paper");

    @Test
    void testStartMapsPaperInputsAndCloseStopsRuntime(@TempDir Path dir) throws Exception {
        var runtime = Mockito.mock(KansokushaRuntime.class);
        var api = Mockito.mock(KansokushaApi.class);
        Mockito.when(runtime.api()).thenReturn(api);
        var reporter = Mockito.mock(AdministratorReporter.class);
        var publication = Mockito.mock(PaperRuntimeLifecycle.ApiPublication.class);
        var lifecycle = new PaperRuntimeLifecycle(
            dir,
            SERVER_KEY,
            reporter,
            (dataDirectory, serverKey, actualReporter) -> {
                Assertions.assertEquals(dir, dataDirectory);
                Assertions.assertEquals(SERVER_KEY, serverKey);
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
        var shutdownOrder = Mockito.inOrder(publication, runtime);
        shutdownOrder.verify(publication).unpublish(api);
        shutdownOrder.verify(runtime).close();

        lifecycle.close();
        Mockito.verifyNoMoreInteractions(runtime);
    }

    @Test
    void testShutdownFailureIsReported(@TempDir Path dir) throws Exception {
        var runtime = Mockito.mock(KansokushaRuntime.class);
        var api = Mockito.mock(KansokushaApi.class);
        Mockito.when(runtime.api()).thenReturn(api);
        var reporter = Mockito.mock(AdministratorReporter.class);
        var publication = Mockito.mock(PaperRuntimeLifecycle.ApiPublication.class);
        var failure = new SQLException("close failed");
        Mockito.doThrow(failure).when(runtime).close();

        var lifecycle = new PaperRuntimeLifecycle(
            dir,
            SERVER_KEY,
            reporter,
            (dataDirectory, serverKey, actualReporter) -> runtime,
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
        var publication = Mockito.mock(PaperRuntimeLifecycle.ApiPublication.class);
        var failure = new AssertionError("fatal close failure");
        Mockito.doThrow(failure).when(runtime).close();

        var lifecycle = new PaperRuntimeLifecycle(
            dir,
            SERVER_KEY,
            reporter,
            (dataDirectory, serverKey, actualReporter) -> runtime,
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
    void testStartupFailureDoesNotInstallRuntime(@TempDir Path dir) {
        var reporter = Mockito.mock(AdministratorReporter.class);
        var failure = new SQLException("startup failed");
        var publication = Mockito.mock(PaperRuntimeLifecycle.ApiPublication.class);
        var lifecycle = new PaperRuntimeLifecycle(
            dir,
            SERVER_KEY,
            reporter,
            (dataDirectory, serverKey, actualReporter) -> {
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
}
