package net.okocraft.kansokusha.common.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;

import java.nio.file.Files;
import java.nio.file.Path;

class ConfigLoaderTest {

    @ConfigSerializable
    static class TestConfig {
        String name = "default";
        int number = 10;
    }

    private static ConfigLoader<TestConfig> createLoader(Path filepath) {
        return new ConfigLoader<>(filepath, TestConfig.class, TestConfig::new);
    }

    @Test
    void testLoadFromExistingFile(@TempDir Path dir) throws Exception {
        var filepath = dir.resolve("config.yml");
        Files.writeString(filepath, "name: loaded\nnumber: 42\n");

        var config = createLoader(filepath).load();

        Assertions.assertEquals("loaded", config.name);
        Assertions.assertEquals(42, config.number);
    }

    @Test
    void testLoadMissingFileWritesInitialConfig(@TempDir Path dir) throws Exception {
        var filepath = dir.resolve("config.yml");

        var config = createLoader(filepath).load();

        Assertions.assertEquals("default", config.name);
        Assertions.assertTrue(Files.exists(filepath));
        Assertions.assertEquals("default", createLoader(filepath).load().name);
    }

    @Test
    void testLoadPartialFileUsesFieldDefaults(@TempDir Path dir) throws Exception {
        var filepath = dir.resolve("config.yml");
        Files.writeString(filepath, "name: loaded\n");

        var config = createLoader(filepath).load();

        Assertions.assertEquals("loaded", config.name);
        Assertions.assertEquals(10, config.number);
    }

    @Test
    void testLoadInvalidYamlThrows(@TempDir Path dir) throws Exception {
        var filepath = dir.resolve("config.yml");
        Files.writeString(filepath, "name: [unclosed\n");

        Assertions.assertThrows(ConfigurateException.class, () -> createLoader(filepath).load());
    }

    @Test
    void testLoadMissingFileCreatesParentDirectories(@TempDir Path dir) throws Exception {
        var filepath = dir.resolve("nested").resolve("dir").resolve("config.yml");

        createLoader(filepath).load();

        Assertions.assertTrue(Files.exists(filepath));
    }
}
