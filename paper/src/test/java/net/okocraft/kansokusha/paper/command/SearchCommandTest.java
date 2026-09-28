package net.okocraft.kansokusha.paper.command;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.common.command.SearchCommandSupport;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.common.search.SearchMetadata;
import net.okocraft.kansokusha.common.search.SearchPage;
import net.okocraft.kansokusha.common.search.SearchRequest;
import net.okocraft.kansokusha.common.search.query.SearchQuery;
import net.okocraft.kansokusha.paper.testsupport.CommandTester;
import net.okocraft.kansokusha.paper.testsupport.TestSources;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Covers the Paper-specific wiring. Search behavior itself is tested in {@code SearchCommandSupportTest}.
 */
class SearchCommandTest {

    private static final Key OVERWORLD = Key.key("minecraft", "overworld");

    private final EventSearchBackend backend = Mockito.mock(EventSearchBackend.class);
    private final CommandTester tester = CommandTester.of(
        SearchCommand.createSearchCommand(this.backend, Clock.systemUTC(), ZoneOffset.UTC).build()
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
        ConsoleCommandSender console = Mockito.mock(ConsoleCommandSender.class);

        Assertions.assertThrows(
            CommandSyntaxException.class,
            () -> this.tester.execute(TestSources.ofSenderOnly(console), "search")
        );
    }

    @Test
    void testPlayerRadiusUsesCurrentBlockPositionAndPlayerLimit() throws Exception {
        var player = player();

        Assertions.assertEquals(1, this.tester.execute(TestSources.of(player), "search radius 5"));

        var request = this.lastRequest();
        Assertions.assertEquals(SearchCommandSupport.PLAYER_DEFAULT_LIMIT, request.defaultLimit());
        Assertions.assertEquals(
            Set.of(new SearchQuery.Around(OVERWORLD, 10, -4, 5)),
            request.query().conditions().around()
        );
    }

    @Test
    void testConsoleUsesNonPlayerLimitAndHasNoRadius() throws Exception {
        ConsoleCommandSender console = Mockito.mock(ConsoleCommandSender.class);
        TestSources.grant(console, SearchCommand.PERMISSION);
        var source = TestSources.ofSenderOnly(console);

        Assertions.assertEquals(1, this.tester.execute(source, "search"));
        Assertions.assertEquals(SearchCommandSupport.NON_PLAYER_DEFAULT_LIMIT, this.lastRequest().defaultLimit());
        Assertions.assertEquals(0, this.tester.execute(source, "search radius 5"));
        Assertions.assertFalse(this.tester.suggest(source, "search ").contains("radius"));
    }

    @Test
    void testSuggestionsReplaceOnlyTheCurrentToken() {
        var source = TestSources.of(player());

        Assertions.assertEquals(List.of("newest", "oldest"), this.tester.suggest(source, "search order "));
        Assertions.assertTrue(this.tester.suggest(source, "search ").contains("radius"));
    }

    private SearchRequest lastRequest() {
        var captor = ArgumentCaptor.forClass(SearchRequest.class);
        Mockito.verify(this.backend, Mockito.atLeastOnce()).search(captor.capture());
        return captor.getValue();
    }

    private static Player player() {
        Player player = Mockito.mock(Player.class);
        World world = Mockito.mock(World.class);
        Mockito.when(world.getKey()).thenReturn(NamespacedKey.minecraft("overworld"));
        Mockito.when(player.getLocation()).thenReturn(new Location(world, 10.8, 64.2, -3.2));
        TestSources.grant(player, SearchCommand.PERMISSION);
        return player;
    }
}
