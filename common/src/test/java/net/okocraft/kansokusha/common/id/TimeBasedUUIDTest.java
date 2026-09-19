package net.okocraft.kansokusha.common.id;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TimeBasedUUIDTest {

    @Test
    void testGenerateV7() {
        UUID uuid = TimeBasedUUID.generate();
        assertEquals(7, uuid.version(), "UUID version should be 7");
        assertEquals(2, uuid.variant(), "UUID variant should be 2 (IETF)");
    }

    @Test
    void testGetTimestamp() {
        // 2024-05-01T00:00:00Z -> 1714521600000 ms
        long timestamp = 1714521600000L;
        // UUID v7 format:
        // unix_ts_ms (48 bits): 1714521600000 = 0x0000018f3216c000
        // version (4 bits): 7
        // rand_a (12 bits): 0
        // variant (2 bits): 2
        // rand_b (62 bits): 0
        long msb = (timestamp << 16) | 0x7000L;
        long lsb = 0x8000000000000000L;
        UUID uuid = new UUID(msb, lsb);

        assertEquals(timestamp, TimeBasedUUID.getTimestamp(uuid), "Extracted timestamp should match the fixed timestamp");
    }

    @RepeatedTest(5)
    void testUniqueness() {
        int count = 10000;
        Set<UUID> uuids = new HashSet<>(count);
        for (int i = 0; i < count; i++) {
            UUID uuid = TimeBasedUUID.generate();
            assertTrue(uuids.add(uuid), "UUID should be unique: " + uuid);
        }
    }

    @RepeatedTest(5)
    void testConcurrentUniqueness() throws InterruptedException {
        int threadCount = 10;
        int idPerThread = 10000;
        int totalIds = threadCount * idPerThread;
        Set<UUID> uuids = ConcurrentHashMap.newKeySet(totalIds);
        
        try (ExecutorService executor = Executors.newFixedThreadPool(threadCount)) {
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    for (int j = 0; j < idPerThread; j++) {
                        uuids.add(TimeBasedUUID.generate());
                    }
                });
            }
            executor.shutdown();
            assertTrue(executor.awaitTermination(1, TimeUnit.MINUTES));
        }
        
        assertEquals(totalIds, uuids.size(), "All generated UUIDs should be unique");
    }
    
    @Test
    void testNonV7ThrowsException() {
        UUID v4 = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> TimeBasedUUID.getTimestamp(v4));
    }
}
