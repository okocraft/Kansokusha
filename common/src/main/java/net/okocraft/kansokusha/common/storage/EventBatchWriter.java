package net.okocraft.kansokusha.common.storage;

import net.okocraft.kansokusha.common.event.AcceptedEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.sql.SQLException;
import java.util.List;

@FunctionalInterface
@ApiStatus.Internal
@NotNullByDefault
public interface EventBatchWriter {

    int append(List<AcceptedEvent> events) throws SQLException;
}
