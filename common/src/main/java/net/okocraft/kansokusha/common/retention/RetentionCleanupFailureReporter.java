package net.okocraft.kansokusha.common.retention;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

@FunctionalInterface
@ApiStatus.Internal
@NotNullByDefault
public interface RetentionCleanupFailureReporter {

    void report(Throwable failure);
}
