package net.okocraft.kansokusha.common.reporting;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

@FunctionalInterface
@ApiStatus.Internal
@NotNullByDefault
public interface AdministratorReporter {

    void report(String message, Throwable failure);
}
