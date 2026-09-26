package net.okocraft.kansokusha.common.id;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.UUID;

class TimeBasedUUIDTest {

    @Test
    void testGenerateProducesUniqueUuidV7Values() {
        var count = 10_000;
        var uuids = new HashSet<UUID>(count);

        for (var index = 0; index < count; index++) {
            var uuid = TimeBasedUUID.generate();
            Assertions.assertEquals(7, uuid.version());
            Assertions.assertEquals(2, uuid.variant());
            Assertions.assertTrue(uuids.add(uuid), "Duplicate UUID: " + uuid);
        }
    }
}
