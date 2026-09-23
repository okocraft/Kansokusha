package net.okocraft.kansokusha.velocity.builtin;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class VelocityServerKeyCodecTest {

    @Test
    void testServerNameIsLowerCasedIntoKeyValue() {
        Assertions.assertEquals(
            "kansokusha:velocity-server/lobby",
            VelocityServerKeyCodec.encode("lobby").orElseThrow().asString()
        );
        Assertions.assertEquals(
            "kansokusha:velocity-server/survival-1",
            VelocityServerKeyCodec.encode("Survival-1").orElseThrow().asString()
        );
    }

    @Test
    void testNamesWithInvalidKeyCharactersAreRejected() {
        Assertions.assertTrue(VelocityServerKeyCodec.encode("東京").isEmpty());
        Assertions.assertTrue(VelocityServerKeyCodec.encode("my server").isEmpty());
    }
}
