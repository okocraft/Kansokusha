package net.okocraft.kansokusha.paper.builtin;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

/**
 * A listener that aggregates events in memory before submitting them.
 */
@ApiStatus.Internal
@NotNullByDefault
interface PaperBufferedListener {

    /**
     * Submits the buffered events whose aggregation window has ended.
     */
    void flushDue();

    /**
     * Submits every buffered event. Must be called before the API is closed.
     */
    void flushAll();
}
