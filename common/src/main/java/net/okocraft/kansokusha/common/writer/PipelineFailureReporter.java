package net.okocraft.kansokusha.common.writer;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

@FunctionalInterface
@ApiStatus.Internal
@NotNullByDefault
public interface PipelineFailureReporter {

    void report(Throwable failure);
}
