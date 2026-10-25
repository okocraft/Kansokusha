package net.okocraft.kansokusha.common.command;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import java.util.UUID;

class SearchNavigationTest {
    @Test
    void testPagesAreIsolatedAndDiscardedWithSession() {
        var alice = UUID.randomUUID();
        var bob = UUID.randomUUID();
        try {
            SearchNavigation.remember(alice, "/kansokusha inspect page minecraft:overworld 1 64 2 first container");
            SearchNavigation.remember(bob, "/kansokusha search user Bob");
            Assertions.assertTrue(SearchNavigation.last(alice).orElseThrow().endsWith("first container"));
            Assertions.assertEquals("/kansokusha search user Bob", SearchNavigation.last(bob).orElseThrow());
            SearchNavigation.forget(alice);
            Assertions.assertTrue(SearchNavigation.last(alice).isEmpty());
            Assertions.assertTrue(SearchNavigation.last(bob).isPresent());
        } finally { SearchNavigation.clear(); }
    }
}
