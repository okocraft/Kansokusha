package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

class PaperBuiltInListenersTest {

    private static final Key SERVER_KEY = Key.key("example", "paper");

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Bootstrap.validate();
    }

    @Test
    void testEveryListenerRegistersItsEventTypes() {
        var api = new RegistrationRecordingApi();

        var listenerClasses = new HashSet<Class<?>>();
        for (var factory : PaperBuiltInListeners.FACTORIES) {
            listenerClasses.add(factory.apply(api, SERVER_KEY).getClass());
        }

        Assertions.assertEquals(PaperBuiltInListeners.FACTORIES.size(), listenerClasses.size());
        Assertions.assertEquals(49, api.registered.size());
        Assertions.assertTrue(api.registered.stream().allMatch(
            definition -> definition.key().namespace().equals("kansokusha")
                && definition.payloadGeneration().equals(PayloadGeneration.FIRST)
        ));
    }

    private static final class RegistrationRecordingApi implements KansokushaApi {

        private final Set<EventTypeDefinition> registered = new HashSet<>();

        @Override
        public Optional<Key> localServerKey() {
            return Optional.of(SERVER_KEY);
        }

        @Override
        public void registerEventType(EventTypeDefinition definition) {
            this.registered.add(definition);
        }

        @Override
        public boolean submit(EventSubmission submission) {
            return true;
        }
    }
}
