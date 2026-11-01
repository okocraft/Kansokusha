package net.okocraft.kansokusha.common.search;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

/**
 * Thrown when the retention configuration is applied while a previous application is still running.
 */
@ApiStatus.Internal
@NotNullByDefault
public final class RetentionAlreadyRunningException extends IllegalStateException {

    public RetentionAlreadyRunningException() {
        super("The retention configuration is already being applied.");
    }
}
