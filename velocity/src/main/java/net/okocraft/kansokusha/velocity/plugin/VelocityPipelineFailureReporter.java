package net.okocraft.kansokusha.velocity.plugin;

import net.okocraft.kansokusha.common.writer.PipelineFailureReporter;
import org.slf4j.Logger;

import java.util.Objects;

final class VelocityPipelineFailureReporter implements PipelineFailureReporter {

    private final Logger logger;

    VelocityPipelineFailureReporter(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public void report(Throwable failure) {
        this.logger.error(
            "Kansokusha event writer failed; event recording is unavailable.",
            failure
        );
    }
}
