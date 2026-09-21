package net.okocraft.kansokusha.common.storage;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.sql.SQLException;
import java.time.Instant;

@FunctionalInterface
@ApiStatus.Internal
@NotNullByDefault
public interface RetentionCleaner {

    int deleteExpired(Instant cutoff, int maxRowsPerPass) throws SQLException;
}
