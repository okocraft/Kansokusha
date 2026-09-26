package net.okocraft.kansokusha.common.storage;

import org.jetbrains.annotations.NotNullByDefault;

/**
 * Snapshot of the persistent storage state for operational diagnostics.
 */
@NotNullByDefault
public record StorageHealth(
    long eventCount,
    String databaseSize,
    long blockSize,
    long totalBlocks,
    long usedBlocks,
    long freeBlocks,
    String walSize
) {
}
