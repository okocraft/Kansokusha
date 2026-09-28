package net.okocraft.kansokusha.paper.api;

import net.kyori.adventure.key.Key;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PaperKansokushaTest {

    @Test
    void testNamespacedKeyConversionIsLossless() {
        var paper = new NamespacedKey("example.namespace", "nested/path.value-test");

        var adventure = PaperKansokusha.key(paper);
        var convertedBack = PaperKansokusha.namespacedKey(adventure);

        Assertions.assertEquals(Key.key("example.namespace", "nested/path.value-test"), adventure);
        Assertions.assertEquals(paper, convertedBack);
    }

}
