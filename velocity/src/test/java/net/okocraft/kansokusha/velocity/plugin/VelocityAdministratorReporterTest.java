package net.okocraft.kansokusha.velocity.plugin;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;

class VelocityAdministratorReporterTest {

    @Test
    void testFailureIsLoggedAsError() {
        var logger = Mockito.mock(Logger.class);
        var failure = new IllegalStateException("storage failed");

        new VelocityAdministratorReporter(logger).report("Kansokusha failed.", failure);

        Mockito.verify(logger).error("Kansokusha failed.", failure);
    }
}
