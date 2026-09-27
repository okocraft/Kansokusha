package net.okocraft.kansokusha.common.command;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.common.search.SearchMetadata;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
}
