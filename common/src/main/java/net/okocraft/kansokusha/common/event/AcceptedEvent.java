package net.okocraft.kansokusha.common.event;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.event.EventSubmission;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

@NotNullByDefault
public record AcceptedEvent(EventSubmission submission, Key retentionPolicyKey) {

    public AcceptedEvent {
        Objects.requireNonNull(submission, "submission");
        Objects.requireNonNull(retentionPolicyKey, "retentionPolicyKey");
    }
}
