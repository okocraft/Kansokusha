package net.okocraft.kansokusha.common.search.query;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.common.search.query.SearchQuery.ActorKind;
import net.okocraft.kansokusha.common.search.query.SearchQuery.Around;
import net.okocraft.kansokusha.common.search.query.SearchQuery.Order;
import net.okocraft.kansokusha.common.search.query.SearchQuery.Position;
import net.okocraft.kansokusha.common.search.query.SearchQuery.TimeRange;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

class SearchQueryParserTest {

    private static final Instant NOW = Instant.parse("2026-09-27T01:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    @Test
    void testRepeatedFieldsAreOrGroupsAndDifferentFieldsRemainSeparate() {
        var query = parse(
            "user Alice user Bob "
                + "action block_break action kansokusha:block_place "
                + "target minecraft:stone target minecraft:dirt"
        );

        Assertions.assertEquals(Set.of("Alice", "Bob"), query.conditions().users());
        Assertions.assertEquals(
            Set.of(
                Key.key("kansokusha", "block_break"),
                Key.key("kansokusha", "block_place")
            ),
            query.conditions().actions()
        );
        Assertions.assertEquals(
            Set.of(Key.key("minecraft", "stone"), Key.key("minecraft", "dirt")),
            query.conditions().targets()
        );
    }

    @Test
    void testIncludeIsTargetAlias() {
        var query = parse("include minecraft:stone target minecraft:dirt");

        Assertions.assertEquals(
            Set.of(Key.key("minecraft", "stone"), Key.key("minecraft", "dirt")),
            query.conditions().targets()
        );
    }

    @Test
    void testExcludeConditionsAreGroupedByField() {
        var query = parse(
            "exclude user Notch exclude user Dinnerbone "
                + "exclude action paper_chat "
                + "exclude target minecraft:dirt exclude include minecraft:stone "
                + "exclude actor-type minecraft:creeper"
        );

        Assertions.assertEquals(Set.of("Notch", "Dinnerbone"), query.exclusions().users());
        Assertions.assertEquals(
            Set.of(Key.key("kansokusha", "paper_chat")),
            query.exclusions().actions()
        );
        Assertions.assertEquals(
            Set.of(Key.key("minecraft", "dirt"), Key.key("minecraft", "stone")),
            query.exclusions().targets()
        );
        Assertions.assertEquals(
            Set.of(Key.key("minecraft", "creeper")),
            query.exclusions().actorTypes()
        );
    }

    @Test
    void testIdenticalIncludeAndExcludeConditionIsRejected() {
        Assertions.assertThrows(
            SearchQueryParseException.class,
            () -> parse("target minecraft:dirt exclude target minecraft:dirt")
        );
        Assertions.assertThrows(
            SearchQueryParseException.class,
            () -> parse("action block_break exclude action kansokusha:block_break")
        );
    }

    @Test
    void testActionNamespaceOmissionOnlyDefaultsToKansokusha() {
        var query = parse("action block_break action example:custom_event");

        Assertions.assertEquals(
            Set.of(
                Key.key("kansokusha", "block_break"),
                Key.key("example", "custom_event")
            ),
            query.conditions().actions()
        );
    }

    @Test
    void testActorUuidKindTypeAndAdventureKeys() {
        var uuid = UUID.fromString("018f0c9a-8a41-7d2c-9a36-6c00fb3dcb62");
        var query = parse(
            "actor-uuid " + uuid
                + " actor-kind entity"
                + " actor-type minecraft:creeper"
                + " world minecraft:overworld"
        );

        Assertions.assertEquals(Set.of(uuid), query.conditions().actorUuids());
        Assertions.assertEquals(Set.of(ActorKind.ENTITY), query.conditions().actorKinds());
        Assertions.assertEquals(
            Set.of(Key.key("minecraft", "creeper")),
            query.conditions().actorTypes()
        );
        Assertions.assertEquals(
            Set.of(Key.key("minecraft", "overworld")),
            query.conditions().worlds()
        );
    }

    @Test
    void testPositionAroundAndRadius() {
        var query = parse(
            "position minecraft:overworld 10 64 -20 "
                + "around minecraft:the_nether -5 8 12 radius 30"
        );

        Assertions.assertEquals(
            Set.of(new Position(Key.key("minecraft", "overworld"), 10, 64, -20)),
            query.conditions().positions()
        );
        Assertions.assertEquals(
            Set.of(new Around(Key.key("minecraft", "the_nether"), -5, 8, 12)),
            query.conditions().around()
        );
        Assertions.assertEquals(Set.of(30), query.conditions().radii());
    }

    @Test
    void testRelativeDurationsAndRepeatedTimeRanges() {
        var query = parse("time 30m time 1w2d6h");

        Assertions.assertEquals(
            Set.of(
                TimeRange.bounded(NOW.minus(Duration.ofMinutes(30)), NOW),
                TimeRange.bounded(NOW.minus(Duration.ofHours(222)), NOW)
            ),
            query.conditions().timeRanges()
        );
    }

    @Test
    void testRelativeRangeIsNormalizedToOlderThenNewer() {
        var expected = TimeRange.bounded(NOW.minus(Duration.ofHours(2)), NOW.minus(Duration.ofHours(1)));

        Assertions.assertEquals(
            Set.of(expected),
            parse("time 1h-2h").conditions().timeRanges()
        );
        Assertions.assertEquals(
            Set.of(expected),
            parse("time 2h-1h").conditions().timeRanges()
        );
    }

    @Test
    void testTodayAndYesterdayUseSearchTimezone() {
        var query = parse("time today time yesterday");

        Assertions.assertEquals(
            Set.of(
                TimeRange.bounded(
                    Instant.parse("2026-09-26T15:00:00Z"),
                    Instant.parse("2026-09-27T15:00:00Z")
                ),
                TimeRange.bounded(
                    Instant.parse("2026-09-25T15:00:00Z"),
                    Instant.parse("2026-09-26T15:00:00Z")
                )
            ),
            query.conditions().timeRanges()
        );
    }

    @Test
    void testDateOnlyFromAndToUseStartAndExclusiveNextDay() {
        var range = onlyTime(parse("from 2026-09-01 to 2026-09-27"));

        Assertions.assertEquals(
            Optional.of(Instant.parse("2026-08-31T15:00:00Z")),
            range.fromInclusive()
        );
        Assertions.assertEquals(
            Optional.of(Instant.parse("2026-09-27T15:00:00Z")),
            range.toExclusive()
        );
    }

    @Test
    void testDatetimeWithoutOffsetUsesSearchTimezoneAndOffsetIsPreserved() {
        var range = onlyTime(parse("from 2026-09-27T10:30 to 2026-09-27T12:00+02:00"));

        Assertions.assertEquals(
            Optional.of(Instant.parse("2026-09-27T01:30:00Z")),
            range.fromInclusive()
        );
        Assertions.assertEquals(
            Optional.of(Instant.parse("2026-09-27T10:00:00Z")),
            range.toExclusive()
        );
    }

    @Test
    void testOffsetlessDatetimeChangesWithTimezone() {
        var tokyo = SearchQueryParser.parse(
            "from 2026-09-27T10:30",
            CLOCK,
            ZoneId.of("Asia/Tokyo")
        );
        var utc = SearchQueryParser.parse(
            "from 2026-09-27T10:30",
            CLOCK,
            ZoneOffset.UTC
        );

        Assertions.assertEquals(
            Instant.parse("2026-09-27T01:30:00Z"),
            onlyTime(tokyo).fromInclusive().orElseThrow()
        );
        Assertions.assertEquals(
            Instant.parse("2026-09-27T10:30:00Z"),
            onlyTime(utc).fromInclusive().orElseThrow()
        );
    }

    @Test
    void testTimeAndExplicitBoundsConflict() {
        Assertions.assertThrows(
            SearchQueryParseException.class,
            () -> parse("time 30m from 2026-09-01")
        );
        Assertions.assertThrows(
            SearchQueryParseException.class,
            () -> parse("to 2026-09-27 time 30m")
        );
    }

    @Test
    void testInvalidNumbersRadiusAndLimitAreRejected() {
        assertInvalid("position minecraft:overworld x 64 10");
        assertInvalid("radius nope");
        assertInvalid("radius 0");
        assertInvalid("radius -1");
        assertInvalid("around minecraft:overworld 0 0 0");
        assertInvalid("limit nope");
        assertInvalid("limit 0");
        assertInvalid("limit -1");
    }

    @Test
    void testInvalidUuidKeysEnumsAndDurationsAreRejected() {
        assertInvalid("actor-uuid nope");
        assertInvalid("target INVALID:KEY");
        assertInvalid("actor-kind npc");
        assertInvalid("order sideways");
        assertInvalid("time 0m");
        assertInvalid("time 1x");
        assertInvalid("time 1h-1h");
        assertInvalid("time 1h-2h-3h");
    }

    @Test
    void testQuotedFilterTextAndEscapes() {
        var query = parse("filter \"ban me please\" filter 'other text' filter \"say \\\"hello\\\"\"");

        Assertions.assertEquals(
            Set.of("ban me please", "other text", "say \"hello\""),
            query.conditions().filters()
        );
    }

    @Test
    void testMalformedQuotedInputIsRejected() {
        assertInvalid("filter \"unterminated");
        assertInvalid("filter trailing\\");
    }

    @Test
    void testOrderAndLimit() {
        var defaults = parse("");
        Assertions.assertEquals(Order.NEWEST, defaults.order());
        Assertions.assertTrue(defaults.limit().isEmpty());

        var query = parse("order oldest limit 250");
        Assertions.assertEquals(Order.OLDEST, query.order());
        Assertions.assertEquals(250, query.limit().orElseThrow());
    }

    @Test
    void testSingletonControlModifiersRejectDuplicates() {
        assertInvalid("from 2026-09-01 from 2026-09-02");
        assertInvalid("to 2026-09-01 to 2026-09-02");
        assertInvalid("order newest order oldest");
        assertInvalid("limit 10 limit 20");
    }

    @Test
    void testExplicitRangeMustBeNonEmpty() {
        assertInvalid("from 2026-09-27T10:30 to 2026-09-27T10:30");
        assertInvalid("from 2026-09-28 to 2026-09-27");
    }

    @Test
    void testExcludeSupportsTypedConditionsIncludingTimeAndPosition() {
        var uuid = UUID.fromString("018f0c9a-8a41-7d2c-9a36-6c00fb3dcb62");
        var query = parse(
            "exclude actor-uuid " + uuid
                + " exclude actor-kind block"
                + " exclude world minecraft:overworld"
                + " exclude position minecraft:overworld 1 2 3"
                + " exclude around minecraft:overworld 4 5 6"
                + " exclude radius 7"
                + " exclude time 30m"
                + " exclude filter \"secret text\""
        );

        Assertions.assertEquals(Set.of(uuid), query.exclusions().actorUuids());
        Assertions.assertEquals(Set.of(ActorKind.BLOCK), query.exclusions().actorKinds());
        Assertions.assertEquals(
            Set.of(Key.key("minecraft", "overworld")),
            query.exclusions().worlds()
        );
        Assertions.assertEquals(
            Set.of(new Position(Key.key("minecraft", "overworld"), 1, 2, 3)),
            query.exclusions().positions()
        );
        Assertions.assertEquals(
            Set.of(new Around(Key.key("minecraft", "overworld"), 4, 5, 6)),
            query.exclusions().around()
        );
        Assertions.assertEquals(Set.of(7), query.exclusions().radii());
        Assertions.assertEquals(
            Set.of(TimeRange.bounded(NOW.minus(Duration.ofMinutes(30)), NOW)),
            query.exclusions().timeRanges()
        );
        Assertions.assertEquals(Set.of("secret text"), query.exclusions().filters());
    }

    private static SearchQuery parse(String input) {
        return SearchQueryParser.parse(input, CLOCK, TOKYO);
    }

    private static TimeRange onlyTime(SearchQuery query) {
        Assertions.assertEquals(1, query.conditions().timeRanges().size());
        return query.conditions().timeRanges().iterator().next();
    }

    private static void assertInvalid(String input) {
        Assertions.assertThrows(SearchQueryParseException.class, () -> parse(input));
    }
}
