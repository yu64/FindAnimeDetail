package app.infrastructure.client;

import static org.junit.jupiter.api.Assertions.*;

import app.infrastructure.AnimeSearchException;
import app.usecase.IAnnictClient.SearchCondition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class IAnnictDefTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"), ZoneOffset.UTC);
    private static final SearchCondition CONDITION = new SearchCondition("テスト", List.of("2026-autumn"), null, false);

    static class Stub implements IAnnictDef {
        final ArrayDeque<JsonNode> responses = new ArrayDeque<>();
        final List<GraphQlRequest> requests = new ArrayList<>();
        Stub(String... json) throws Exception {
            for (String item : json) responses.add(new ObjectMapper().readTree(item));
        }
        public JsonNode execute(GraphQlRequest request) {
            requests.add(request);
            return responses.remove();
        }
    }

    static String works(int id, String cursor) {
        return """
            {"data":{"searchWorks":{"nodes":[{"annictId":%d,"title":"テスト","officialSiteUrl":null}],
            "pageInfo":{"hasNextPage":%s,"endCursor":"%s"}}}}
            """.formatted(id, cursor != null, cursor);
    }

    static String program(int channel, String date) {
        return """
            {"startedAt":"%s","channel":{"annictId":%d,"name":"局%d"}}
            """.formatted(date, channel, channel);
    }

    static String programs(String cursor, String... programs) {
        return """
            {"data":{"searchWorks":{"nodes":[{"programs":{"nodes":[%s],
            "pageInfo":{"hasNextPage":%s,"endCursor":"%s"}}}]}}}
            """.formatted(String.join(",", programs), cursor != null, cursor);
    }

    @Test
    void pagesWorksAndProgramsAndChoosesPriorityBeforeDate() throws Exception {
        var stub = new Stub(works(1, "work-next"),
            programs("program-next", program(7, "2026-10-01T12:00:00Z")),
            programs(null, program(19, "2026-10-02T16:00:00Z")),
            works(2, null), programs(null));

        var results = stub.search(CONDITION, List.of(19, 7), CLOCK);

        assertEquals(1, results.size());
        var first = results.getFirst().firstBroadcast();
        assertEquals(19, first.channelId());
        assertEquals("2026-10-03T01:00+09:00[Asia/Tokyo]", first.startsAt().toString());
        assertEquals(java.time.DayOfWeek.SATURDAY, first.startsAt().getDayOfWeek());
        assertEquals("program-next", stub.requests.get(2).variables().get("after"));
        assertEquals("work-next", stub.requests.get(3).variables().get("after"));
        assertEquals(List.of("2026-autumn"), stub.requests.getFirst().variables().get("seasons"));
        assertFalse(stub.requests.stream().anyMatch(r -> r.query().contains("episode")));
        assertTrue(stub.responses.isEmpty());
    }

    @Test
    void futureFilterDoesNotReplacePastPremiereWithNextEpisodeOrLowerPriorityChannel() throws Exception {
        var stub = new Stub(works(1, null), programs(null,
            program(19, "2026-09-01T00:00:00Z"),
            program(19, "2026-09-20T00:00:00Z"),
            program(7, "2026-10-01T00:00:00Z")));
        assertTrue(stub.search(new SearchCondition("テスト", List.of(), null, true),
            List.of(19, 7), CLOCK).isEmpty());
    }

    @Test
    void minimumIsInclusiveAndFutureIsStrict() throws Exception {
        var minimum = OffsetDateTime.parse("2026-09-12T00:00:00Z");
        var inclusive = new Stub(works(1, null), programs(null, program(19, minimum.toString())));
        assertEquals(1, inclusive.search(new SearchCondition("テスト", List.of(), minimum, false),
            List.of(19), CLOCK).size());
        var strict = new Stub(works(1, null), programs(null, program(19, minimum.toString())));
        assertTrue(strict.search(new SearchCondition("テスト", List.of(), minimum, true),
            List.of(19), CLOCK).isEmpty());
    }

    @Test
    void missingPreferredStationFallsBackOnlyWithinConfiguredStations() throws Exception {
        var stub = new Stub(works(1, null), programs(null,
            program(99, "2026-09-01T00:00:00Z"), program(7, "2026-10-01T00:00:00Z")));
        assertEquals(7, stub.search(CONDITION, List.of(19, 7), CLOCK)
            .getFirst().firstBroadcast().channelId());
        var unknown = new Stub(works(1, null), programs(null, program(99, "2026-10-01T00:00:00Z")));
        assertTrue(unknown.search(CONDITION, List.of(19, 7), CLOCK).isEmpty());
    }

    @Test
    void excludesMissingOrInvalidBroadcastFieldsWithoutDateFilters() throws Exception {
        for (String incomplete : List.of(
            "{\"channel\":{\"annictId\":19,\"name\":\"局\"}}",
            "{\"startedAt\":null,\"channel\":{\"annictId\":19,\"name\":\"局\"}}",
            program(19, ""), program(19, "invalid"),
            "{\"startedAt\":\"2026-10-01T00:00:00Z\",\"channel\":null}",
            "{\"startedAt\":\"2026-10-01T00:00:00Z\",\"channel\":{\"annictId\":19}}",
            "{\"startedAt\":\"2026-10-01T00:00:00Z\",\"channel\":{\"annictId\":19,\"name\":null}}",
            "{\"startedAt\":\"2026-10-01T00:00:00Z\",\"channel\":{\"annictId\":19,\"name\":\" \"}}")) {
            var stub = new Stub(works(1, null), programs(null, incomplete));
            assertTrue(stub.search(CONDITION, List.of(19), CLOCK).isEmpty(), incomplete);
        }
    }

    @Test
    void incompleteProgramsDoNotPreventFindingValidBroadcastsOnLaterPages() throws Exception {
        var stub = new Stub(works(1, null), programs("next", program(19, "")),
            programs(null, program(7, "2026-10-01T00:00:00Z")));
        assertEquals(7, stub.search(CONDITION, List.of(19, 7), CLOCK)
            .getFirst().firstBroadcast().channelId());
    }

    @Test
    void excludesUnknownDatesWhenMinimumIsSpecified() throws Exception {
        var stub = new Stub(works(1, null), programs(null));
        assertTrue(stub.search(new SearchCondition("テスト", List.of(),
            OffsetDateTime.parse("2026-09-12T00:00:00Z"), false), List.of(19), CLOCK).isEmpty());
    }

    @Test
    void rejectsGraphQlErrorsAndBrokenPagination() throws Exception {
        var error = new Stub("{\"errors\":[{\"message\":\"denied\"}],\"data\":null}");
        assertThrows(AnimeSearchException.class, () -> error.search(CONDITION, List.of(19), CLOCK));
        var pages = new Stub(works(1, "same"), programs(null), works(1, "same"));
        assertThrows(AnimeSearchException.class, () -> pages.search(CONDITION, List.of(19), CLOCK));
    }
}
