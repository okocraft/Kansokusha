package net.okocraft.kansokusha.velocity.plugin;

import net.okocraft.kansokusha.common.reporting.AdministratorReporter;
import org.slf4j.Logger;

import java.util.Objects;

final class VelocityAdministratorReporter implements AdministratorReporter {

    private final Logger logger;

    VelocityAdministratorReporter(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public void report(String message, Throwable failure) {
        this.logger.error(message, failure);
    }
}
