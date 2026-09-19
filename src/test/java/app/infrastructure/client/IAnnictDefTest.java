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

    static String works(int id, String cursor, String programCursor, String... broadcasts) {
        return """
            {"data":{"searchWorks":{"nodes":[{"annictId":%d,"title":"テスト","officialSiteUrl":null,"programs":{"nodes":[%s],"pageInfo":{"hasNextPage":%s,"endCursor":"%s"}}}],
            "pageInfo":{"hasNextPage":%s,"endCursor":"%s"}}}}
            """.formatted(id, String.join(",", broadcasts), programCursor != null, programCursor, cursor != null, cursor);
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
    void sendsAllTitlesAndBroadcastsInOneRequestWhenFirstPageSuffices() throws Exception {
        var stub = new Stub(works(15804, null, "unused", program(19, "2026-10-01T00:00:00Z")));
        var condition = new SearchCondition(List.of(" シャングリラ ", "SHIROBAKO", "シャングリラ"),
            List.of(), null, false);
        assertEquals(1, stub.search(condition, List.of(19, 7), CLOCK).size());
        assertEquals(1, stub.requests.size());
        assertEquals(List.of("シャングリラ", "SHIROBAKO"), stub.requests.getFirst().variables().get("titles"));
        assertTrue(stub.requests.getFirst().query().contains("programs(first: 50"));
    }

    @Test
    void preservesKnownDateWhenStationIsUnknownAndKnownStationWhenDateIsUnknown() throws Exception {
        var unknownStation = new Stub(works(1, null, null,
            "{\"startedAt\":\"2026-10-01T00:00:00Z\",\"channel\":null}"));
        var first = unknownStation.search(CONDITION, List.of(19), CLOCK).getFirst().firstBroadcast();
        assertNotNull(first.startsAt());
        assertNull(first.channelName());
        var unknownDate = new Stub(works(1, null, null, program(19, "invalid")));
        first = unknownDate.search(CONDITION, List.of(19), CLOCK).getFirst().firstBroadcast();
        assertNull(first.startsAt());
        assertEquals("局19", first.channelName());
    }

    @Test
    void findsDatedBroadcastOnLaterPageForSameStation() throws Exception {
        var stub = new Stub(works(1, null, "next", program(19, "")),
            programs(null, program(19, "2026-10-01T00:00:00Z")));
        assertNotNull(stub.search(CONDITION, List.of(19), CLOCK).getFirst().firstBroadcast().startsAt());
        assertEquals(2, stub.requests.size());
    }

    @Test
    void pagesWorksAndProgramsAndChoosesPriorityBeforeDate() throws Exception {
        var stub = new Stub(works(1, "work-next", "program-next", program(7, "2026-10-01T12:00:00Z")),
            programs(null, program(19, "2026-10-02T16:00:00Z")),
            works(2, null, null));

        var results = stub.search(CONDITION, List.of(19, 7), CLOCK);

        assertEquals(2, results.size());
        assertNull(results.get(1).firstBroadcast());
        var first = results.getFirst().firstBroadcast();
        assertEquals(19, first.channelId());
        assertEquals("2026-10-03T01:00+09:00[Asia/Tokyo]", first.startsAt().toString());
        assertEquals(java.time.DayOfWeek.SATURDAY, first.startsAt().getDayOfWeek());
        assertEquals("program-next", stub.requests.get(1).variables().get("after"));
        assertEquals("work-next", stub.requests.get(2).variables().get("after"));
        assertEquals(List.of("2026-autumn"), stub.requests.getFirst().variables().get("seasons"));
        assertFalse(stub.requests.stream().anyMatch(r -> r.query().contains("episode")));
        assertTrue(stub.responses.isEmpty());
    }

    @Test
    void futureFilterDoesNotReplacePastPremiereWithNextEpisodeOrLowerPriorityChannel() throws Exception {
        var stub = new Stub(works(1, null, null,
            program(19, "2026-09-01T00:00:00Z"),
            program(19, "2026-09-20T00:00:00Z"),
            program(7, "2026-10-01T00:00:00Z")));
        assertTrue(stub.search(new SearchCondition("テスト", List.of(), null, true),
            List.of(19, 7), CLOCK).isEmpty());
    }

    @Test
    void minimumIsInclusiveAndFutureIsStrict() throws Exception {
        var minimum = OffsetDateTime.parse("2026-09-12T00:00:00Z");
        var inclusive = new Stub(works(1, null, null, program(19, minimum.toString())));
        assertEquals(1, inclusive.search(new SearchCondition("テスト", List.of(), minimum, false),
            List.of(19), CLOCK).size());
        var strict = new Stub(works(1, null, null, program(19, minimum.toString())));
        assertTrue(strict.search(new SearchCondition("テスト", List.of(), minimum, true),
            List.of(19), CLOCK).isEmpty());
    }

    @Test
    void missingPreferredStationFallsBackOnlyWithinConfiguredStations() throws Exception {
        var stub = new Stub(works(1, null, null,
            program(99, "2026-09-01T00:00:00Z"), program(7, "2026-10-01T00:00:00Z")));
        assertEquals(7, stub.search(CONDITION, List.of(19, 7), CLOCK)
            .getFirst().firstBroadcast().channelId());
        var unknown = new Stub(works(1, null, null, program(99, "2026-10-01T00:00:00Z")));
        assertNull(unknown.search(CONDITION, List.of(19, 7), CLOCK).getFirst().firstBroadcast());
    }

    @Test
    void retainsMissingOrInvalidBroadcastFieldsWithoutDateFilters() throws Exception {
        for (String incomplete : List.of(
            "{\"channel\":{\"annictId\":19,\"name\":\"局\"}}",
            "{\"startedAt\":null,\"channel\":{\"annictId\":19,\"name\":\"局\"}}",
            program(19, ""), program(19, "invalid"),
            "{\"startedAt\":\"2026-10-01T00:00:00Z\",\"channel\":null}",
            "{\"startedAt\":\"2026-10-01T00:00:00Z\",\"channel\":{\"annictId\":19}}",
            "{\"startedAt\":\"2026-10-01T00:00:00Z\",\"channel\":{\"annictId\":19,\"name\":null}}",
            "{\"startedAt\":\"2026-10-01T00:00:00Z\",\"channel\":{\"annictId\":19,\"name\":\" \"}}")) {
            var stub = new Stub(works(1, null, null, incomplete));
            assertEquals(1, stub.search(CONDITION, List.of(19), CLOCK).size(), incomplete);
        }
    }

    @Test
    void incompleteProgramsDoNotPreventFindingValidBroadcastsOnLaterPages() throws Exception {
        var stub = new Stub(works(1, null, "next", program(19, "")),
            programs(null, program(7, "2026-10-01T00:00:00Z")));
        var first = stub.search(CONDITION, List.of(19, 7), CLOCK).getFirst().firstBroadcast();
        assertEquals(19, first.channelId());
        assertNull(first.startsAt());
        assertEquals(2, stub.requests.size());
    }

    @Test
    void retainsUnknownDatesWhenMinimumIsSpecified() throws Exception {
        var stub = new Stub(works(1, null, null));
        assertEquals(1, stub.search(new SearchCondition("テスト", List.of(),
            OffsetDateTime.parse("2026-09-12T00:00:00Z"), false), List.of(19), CLOCK).size());
    }

    @Test
    void rejectsGraphQlErrorsAndBrokenPagination() throws Exception {
        var error = new Stub("{\"errors\":[{\"message\":\"denied\"}],\"data\":null}");
        assertThrows(AnimeSearchException.class, () -> error.search(CONDITION, List.of(19), CLOCK));
        var pages = new Stub(works(1, "same", null), works(1, "same", null));
        assertThrows(AnimeSearchException.class, () -> pages.search(CONDITION, List.of(19), CLOCK));
    }
}
