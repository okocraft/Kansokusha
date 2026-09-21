package net.okocraft.kansokusha.paper.plugin;

import net.okocraft.kansokusha.common.reporting.AdministratorReporter;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

final class PaperAdministratorReporter implements AdministratorReporter {

    private final Logger logger;

    PaperAdministratorReporter(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public void report(String message, Throwable failure) {
        this.logger.log(Level.SEVERE, message, failure);
    }
}
