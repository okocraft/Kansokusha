package net.okocraft.kansokusha.paper.plugin;

import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

class PaperServerIdentityTest {

    @Test
    void testConfiguredServerKeyTakesPrecedence(@TempDir Path dir) {
        var configured = Key.key("example", "paper");

        var resolved = PaperServerIdentity.resolve(
            Optional.of(configured),
            dir.resolve("ignored-directory")
        );

        Assertions.assertSame(configured, resolved);
    }

    @Test
    void testDirectoryNameIsUsedWhenServerKeyIsNotConfigured(@TempDir Path dir) {
        var resolved = PaperServerIdentity.resolve(
            Optional.empty(),
            dir.resolve("survival")
        );

        Assertions.assertEquals(Key.key("kansokusha", "survival"), resolved);
    }

    @Test
    void testInvalidDirectoryNameRequiresExplicitServerKey(@TempDir Path dir) {
        var error = Assertions.assertThrows(
            IllegalStateException.class,
            () -> PaperServerIdentity.resolve(Optional.empty(), dir.resolve("Invalid Server"))
        );

        Assertions.assertTrue(error.getMessage().contains("Invalid Server"));
        Assertions.assertTrue(error.getMessage().contains("server-key"));
    }
}
