package net.okocraft.kansokusha.paper.inspection;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.common.command.SearchCommandSupport;
import net.okocraft.kansokusha.common.search.query.SearchQuery;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Set;

class InspectionSearchRequestFactoryTest {

    @Test
    void testCreatesExactPositionNewestPlayerDefaultRequest() {
        var world = Key.key("minecraft", "overworld");
        var visibleTypes = Set.of(
            Key.key("kansokusha", "block_break"),
            Key.key("example", "custom")
        );

        var request = InspectionSearchRequestFactory.create(
            new InspectionTarget(world, 123, 64, -456),
            visibleTypes
        );

        Assertions.assertEquals(SearchQuery.Order.NEWEST, request.query().order());
        Assertions.assertEquals(Set.of(world), request.query().conditions().worlds());
        Assertions.assertEquals(
            Set.of(new SearchQuery.Position(world, 123, 64, -456)),
            request.query().conditions().positions()
        );
        Assertions.assertTrue(request.query().conditions().actions().isEmpty());
        Assertions.assertTrue(request.query().conditions().targets().isEmpty());
        Assertions.assertTrue(request.query().conditions().users().isEmpty());
        Assertions.assertTrue(request.query().conditions().timeRanges().isEmpty());
        Assertions.assertTrue(request.query().exclusions().positions().isEmpty());
        Assertions.assertEquals(visibleTypes, request.constraints().allowedEventTypes());
        Assertions.assertEquals(SearchCommandSupport.PLAYER_DEFAULT_LIMIT, request.limit());
        Assertions.assertTrue(request.cursor().isEmpty());
        Assertions.assertTrue(request.radiusCenter().isEmpty());
    }
}
