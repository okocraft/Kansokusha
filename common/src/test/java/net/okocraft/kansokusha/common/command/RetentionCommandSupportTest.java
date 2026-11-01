package net.okocraft.kansokusha.common.command;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.common.config.KansokushaConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

class RetentionCommandSupportTest {

    @Test
    void testPreviewGroupsEventTypesByPeriodLongestFirst() {
        var archivedLong = Key.key("example", "break");
        var retention = new KansokushaConfig.Retention(
            Map.of(
                archivedLong, Duration.ofDays(180),
                Key.key("example", "place"), Duration.ofDays(180),
                Key.key("example", "fluid"), Duration.ofDays(3),
                Key.key("example", "short_audit"), Duration.ofHours(36)
            ),
            Duration.ofDays(30),
            Set.of(archivedLong, Key.key("example", "short_audit"))
        );

        Assertions.assertEquals(List.of(
            RetentionCommandMessages.PREVIEW_HEADER.asComponent(),
            RetentionCommandMessages.PREVIEW_POLICY.apply(
                "P180D", RetentionCommandMessages.AFTER_ARCHIVED.asComponent(), 1
            ),
            RetentionCommandMessages.PREVIEW_POLICY.apply(
                "P180D", RetentionCommandMessages.AFTER_DELETED.asComponent(), 1
            ),
            RetentionCommandMessages.PREVIEW_POLICY.apply(
                "P3D", RetentionCommandMessages.AFTER_DELETED.asComponent(), 1
            ),
            RetentionCommandMessages.PREVIEW_POLICY.apply(
                "PT36H", RetentionCommandMessages.AFTER_ARCHIVED.asComponent(), 1
            ),
            RetentionCommandMessages.PREVIEW_DEFAULT.apply("P30D"),
            RetentionCommandMessages.PREVIEW_WARNING.asComponent(),
            RetentionCommandMessages.PREVIEW_CONFIRM.asComponent()
        ), RetentionCommandSupport.render(retention));
    }
}
