package net.okocraft.kansokusha.velocity.command;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.okocraft.kansokusha.common.command.SearchCommandSupport;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.common.search.SearchMetadata;
import net.okocraft.kansokusha.common.search.SearchPage;
import net.okocraft.kansokusha.common.search.SearchRequest;
import net.okocraft.kansokusha.velocity.testsupport.CommandTester;
import net.okocraft.kansokusha.velocity.testsupport.TestSources;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Covers the Velocity-specific wiring. Search behavior itself is tested in {@code SearchCommandSupportTest}.
 */
class SearchCommandTest {

    private final EventSearchBackend backend = Mockito.mock(EventSearchBackend.class);
    private final CommandTester tester = CommandTester.of(
        SearchCommand.createSearchCommand(this.backend, Clock.systemUTC(), ZoneOffset.UTC)
    );

    SearchCommandTest() {
        Mockito.when(this.backend.searchMetadata())
            .thenReturn(CompletableFuture.completedFuture(SearchMetadata.empty()));
        Mockito.when(this.backend.search(Mockito.any())).thenReturn(CompletableFuture.completedFuture(
            new SearchPage(List.of(), Optional.empty(), Optional.empty())
        ));
    }

    @Test
    void testSearchPermissionIsRequired() {
        Assertions.assertThrows(
            CommandSyntaxException.class,
            () -> this.tester.execute(TestSources.console(), "search")
        );
    }

    @Test
    void testPlayerAndConsoleLimitsAndNoRadiusOnProxy() throws Exception {
        var player = TestSources.player();
        TestSources.grant(player, SearchCommand.PERMISSION);
        var console = TestSources.console();
        TestSources.grant(console, SearchCommand.PERMISSION);

        Assertions.assertEquals(1, this.tester.execute(player, "search"));
        Assertions.assertEquals(SearchCommandSupport.PLAYER_DEFAULT_LIMIT, this.lastRequest().defaultLimit());
        Assertions.assertEquals(1, this.tester.execute(console, "search"));
        Assertions.assertEquals(SearchCommandSupport.NON_PLAYER_DEFAULT_LIMIT, this.lastRequest().defaultLimit());

        Assertions.assertEquals(0, this.tester.execute(player, "search radius 5"));
        Assertions.assertFalse(this.tester.suggest(player, "search ").contains("radius"));
        Assertions.assertEquals(List.of("newest", "oldest"), this.tester.suggest(player, "search order "));
    }

    private SearchRequest lastRequest() {
        var captor = ArgumentCaptor.forClass(SearchRequest.class);
        Mockito.verify(this.backend, Mockito.atLeastOnce()).search(captor.capture());
        return captor.getValue();
    }
}
