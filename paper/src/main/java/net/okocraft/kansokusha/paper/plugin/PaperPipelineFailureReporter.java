package net.okocraft.kansokusha.paper.plugin;

import net.okocraft.kansokusha.common.writer.PipelineFailureReporter;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

final class PaperPipelineFailureReporter implements PipelineFailureReporter {

    private final Logger logger;

    PaperPipelineFailureReporter(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public void report(Throwable failure) {
        this.logger.log(
            Level.SEVERE,
            "Kansokusha event writer failed; event recording is unavailable.",
            failure
        );
    }
}
