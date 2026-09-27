package net.okocraft.kansokusha.common.command;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.okocraft.kansokusha.common.search.SearchMetadata;
import net.okocraft.kansokusha.common.search.SearchPage;
import net.okocraft.kansokusha.common.search.SearchRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;
import java.util.Set;

class SearchCommandSupportTest {

    private static final Key CHAT = Key.key("kansokusha", "paper_chat");
    private static final Key COMMAND = Key.key("kansokusha", "paper_player_command");

    @Test
    void testEventPermissionDelegatesOnlyExactNodesToPlatformPermissionApi() {
        var metadata = new SearchMetadata(Map.of(
            CHAT, SearchMetadata.EventValues.empty(),
            COMMAND, SearchMetadata.EventValues.empty()
        ));
        var checks = new ArrayList<String>();

        var allowed = SearchCommandSupport.allowedEventTypes(permission -> {
            checks.add(permission);
            // Simulates the final answer from a platform permission API. Whether this TRUE/FALSE
            // came from an exact grant, wildcard grant, or an exact negative override is deliberately
            // not reinterpreted by Kansokusha.
            return permission.equals(SearchCommandSupport.eventPermission(CHAT));
        }, metadata);

        Assertions.assertEquals(Set.of(CHAT), allowed);
        Assertions.assertEquals(
            Set.of(
                "kansokusha.command.search.event.kansokusha:paper_chat",
                "kansokusha.command.search.event.kansokusha:paper_player_command"
            ),
            Set.copyOf(checks)
        );
        Assertions.assertTrue(checks.stream().noneMatch(permission -> permission.contains("*")));
        Assertions.assertTrue(checks.stream().allMatch(
            permission -> permission.startsWith(SearchCommandSupport.EVENT_PERMISSION_PREFIX)
        ));
    }

    @Test
    void testPaginationComponentsRetainQueryAndCursor() {
        var now = Instant.parse("2026-09-27T07:00:00Z");
        var previous = new SearchRequest.Cursor(
            now.minusSeconds(5),
            UUID.fromString("123e4567-e89b-12d3-a456-426614174302"),
            SearchRequest.Direction.PREVIOUS
        );
        var next = new SearchRequest.Cursor(
            now.plusSeconds(5),
            UUID.fromString("123e4567-e89b-12d3-a456-426614174303"),
            SearchRequest.Direction.NEXT
        );
        var page = new SearchPage(List.of(), Optional.of(next), Optional.of(previous));

        var expected = Component.empty()
            .append(SearchCommandMessages.PREVIOUS.asComponent().clickEvent(ClickEvent.runCommand(
                "/kansokusha search action block_break limit 1 __cursor=previous,"
                    + previous.occurredAt().toEpochMilli() + "," + previous.eventId()
            )))
            .append(Component.text(" | "))
            .append(SearchCommandMessages.NEXT.asComponent().clickEvent(ClickEvent.runCommand(
                "/kansokusha search action block_break limit 1 __cursor=next,"
                    + next.occurredAt().toEpochMilli() + "," + next.eventId()
            )));

        Assertions.assertEquals(
            expected,
            SearchCommandSupport.paginationComponent("action block_break limit 1", page)
        );
        Assertions.assertNull(SearchCommandSupport.paginationComponent(
            "",
            new SearchPage(List.of(), Optional.empty(), Optional.empty())
        ));
    }
}
