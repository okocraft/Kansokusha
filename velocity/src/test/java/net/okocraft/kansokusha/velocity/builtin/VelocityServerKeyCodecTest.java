package net.okocraft.kansokusha.velocity.builtin;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

class VelocityServerKeyCodecTest {

    @Test
    void testRoundTripsAllJavaStringShapesWithoutCollisions() {
        var names = List.of(
            "",
            "lobby",
            "東京",
            "\uD83D\uDE00",
            "\uD800",
            "\uDC00",
            "?",
            "\uFFFD"
        );
        var keys = new HashSet<>();

        for (var name : names) {
            var key = VelocityServerKeyCodec.encode(name);
            Assertions.assertTrue(keys.add(key), "collision for " + printable(name));
            Assertions.assertEquals(name, VelocityServerKeyCodec.decode(key));
        }

        Assertions.assertEquals(names.size(), keys.size());
    }

    @Test
    void testEncodingUsesFourLowercaseHexDigitsPerUtf16CodeUnit() {
        Assertions.assertEquals(
            "kansokusha:velocity-server/006c006f006200620079",
            VelocityServerKeyCodec.encode("lobby").asString()
        );
        Assertions.assertEquals(
            "kansokusha:velocity-server/d83dde00",
            VelocityServerKeyCodec.encode("\uD83D\uDE00").asString()
        );
        Assertions.assertEquals(
            "kansokusha:velocity-server/",
            VelocityServerKeyCodec.encode("").asString()
        );
    }

    private static String printable(String value) {
        var result = new StringBuilder();
        value.chars().forEach(codeUnit ->
            result.append(String.format("\\u%04x", codeUnit))
        );
        return result.toString();
    }
}
