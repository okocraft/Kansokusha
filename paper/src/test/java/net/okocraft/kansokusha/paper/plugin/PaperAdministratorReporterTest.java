package net.okocraft.kansokusha.paper.plugin;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

class PaperAdministratorReporterTest {

    @Test
    void testFailureIsLoggedAtSevereLevel() {
        var logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        var record = new AtomicReference<LogRecord>();
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                record.set(logRecord);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        var failure = new IllegalStateException("storage failed");

        new PaperAdministratorReporter(logger).report("Kansokusha failed.", failure);

        var logged = record.get();
        Assertions.assertNotNull(logged);
        Assertions.assertEquals(java.util.logging.Level.SEVERE, logged.getLevel());
        Assertions.assertEquals("Kansokusha failed.", logged.getMessage());
        Assertions.assertSame(failure, logged.getThrown());
    }
}
