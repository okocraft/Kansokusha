package net.okocraft.kansokusha.api.subject;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventSubjectTest {

    private static final UUID PLAYER_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @Test
    void testPlayerSubjectPreservesUniqueId() {
        PlayerSubject subject = new PlayerSubject(PLAYER_ID);

        assertEquals(PLAYER_ID, subject.uniqueId());
    }

    @Test
    void testEventSubjectIsSealedWithPlayerSubjectAsItsOnlyVariant() {
        assertTrue(EventSubject.class.isSealed());
        assertEquals(
            Set.of(PlayerSubject.class),
            Arrays.stream(EventSubject.class.getPermittedSubclasses()).collect(Collectors.toSet())
        );
    }

    @Test
    void testPlayerSubjectRejectsNullUniqueId() {
        assertThrows(NullPointerException.class, () -> new PlayerSubject(null));
    }
}
