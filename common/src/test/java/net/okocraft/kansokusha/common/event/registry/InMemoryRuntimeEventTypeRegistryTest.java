package net.okocraft.kansokusha.common.event.registry;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryRuntimeEventTypeRegistryTest {

    private static final Key EVENT_KEY = Key.key("test", "runtime-event");

    @Test
    void testNewDefinitionIsRegisteredAndCanBeFound() {
        InMemoryRuntimeEventTypeRegistry registry = new InMemoryRuntimeEventTypeRegistry();
        EventTypeDefinition definition = definition(1);

        assertEquals(RegistrationStatus.REGISTERED, registry.register(definition));
        assertEquals(Optional.of(definition), registry.find(EVENT_KEY));
    }

    @Test
    void testSameDefinitionIsIdempotentAndDifferentGenerationConflicts() {
        InMemoryRuntimeEventTypeRegistry registry = new InMemoryRuntimeEventTypeRegistry();
        EventTypeDefinition first = definition(1);

        assertEquals(RegistrationStatus.REGISTERED, registry.register(first));
        assertEquals(RegistrationStatus.ALREADY_REGISTERED, registry.register(definition(1)));
        assertEquals(RegistrationStatus.CONFLICT, registry.register(definition(2)));
        assertEquals(Optional.of(first), registry.find(EVENT_KEY));
    }

    @Test
    void testOnlyExactDefinitionCanBeUnregisteredAndAnotherGenerationCanReplaceIt() {
        InMemoryRuntimeEventTypeRegistry registry = new InMemoryRuntimeEventTypeRegistry();
        EventTypeDefinition first = definition(1);
        EventTypeDefinition next = definition(2);
        registry.register(first);

        assertFalse(registry.unregister(next));
        assertEquals(Optional.of(first), registry.find(EVENT_KEY));
        assertTrue(registry.unregister(first));
        assertTrue(registry.find(EVENT_KEY).isEmpty());
        assertEquals(RegistrationStatus.REGISTERED, registry.register(next));
        assertEquals(Optional.of(next), registry.find(EVENT_KEY));
    }

    @Test
    void testConcurrentDifferentGenerationsRegisterExactlyOneDefinition() throws Exception {
        InMemoryRuntimeEventTypeRegistry registry = new InMemoryRuntimeEventTypeRegistry();
        int threadCount = 8;
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<RegistrationStatus>> results = new ArrayList<>();

        try {
            for (int i = 1; i <= threadCount; i++) {
                EventTypeDefinition definition = definition(i);
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return registry.register(definition);
                }));
            }
            ready.await();
            start.countDown();

            List<RegistrationStatus> statuses = new ArrayList<>();
            for (Future<RegistrationStatus> result : results) {
                statuses.add(result.get());
            }
            assertEquals(1, statuses.stream()
                .filter(status -> status == RegistrationStatus.REGISTERED)
                .count());
            assertEquals(threadCount - 1, statuses.stream()
                .filter(status -> status == RegistrationStatus.CONFLICT)
                .count());
            int registeredIndex = statuses.indexOf(RegistrationStatus.REGISTERED);
            assertEquals(definition(registeredIndex + 1), registry.find(EVENT_KEY).orElseThrow());
        } finally {
            executor.shutdownNow();
        }
    }

    private static EventTypeDefinition definition(int generation) {
        return new EventTypeDefinition(EVENT_KEY, new PayloadGeneration(generation));
    }
}
