package app.infrastructure.client;

import static org.junit.jupiter.api.Assertions.*;

import app.infrastructure.AnimeSearchException;
import app.usecase.IAnnictClient.Anime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class IAnnictDefTest {

  /** 1回の応答を返し、送られたリクエストを記録する。 */
  static class Stub implements IAnnictDef {
    final JsonNode response;
    final List<GraphQlRequest> requests = new ArrayList<>();
    Stub(String json) throws Exception {
      response = new ObjectMapper().readTree(json);
    }
    public JsonNode execute(GraphQlRequest request) {
      requests.add(request);
      return response;
    }
  }

  /** エイリアス w0, w1… ごとの作品一覧を持つ応答。 */
  static String data(String... aliases) {
    var fields = new ArrayList<String>();
    for(int i = 0; i < aliases.length; i++) {
      fields.add("\"w%d\":{\"nodes\":[%s]}".formatted(i, aliases[i]));
    }
    return "{\"data\":{" + String.join(",", fields) + "}}";
  }

  static String work(int id, String... programs) {
    return """
        {"annictId":%d,"title":"作品%d","officialSiteUrl":null,"programs":{"nodes":[%s]}}
        """.formatted(id, id, String.join(",", programs));
  }

  static String program(int channel, String date) {
    return program(channel, date, false);
  }

  static String program(int channel, String date, boolean rebroadcast) {
    return """
        {"startedAt":"%s","rebroadcast":%s,"channel":{"annictId":%d,"name":"局%d"}}
        """.formatted(date, rebroadcast, channel, channel);
  }

  private static Anime only(Stub stub, List<Integer> priorities) {
    return stub.search(List.of("テスト"), priorities).getFirst().works().getFirst();
  }

  // ###########################################################################
  // MARK: 問い合わせ

  @Test
  void sendsEveryWordInOneRequestWithOneAliasPerWord() throws Exception {
    var stub = new Stub(data(work(1), ""));

    var results = stub.search(List.of(" シャングリラ ", "SHIROBAKO", "シャングリラ", " "), List.of(19, 7));

    assertEquals(1, stub.requests.size());
    var request = stub.requests.getFirst();
    assertEquals(List.of("シャングリラ"), request.variables().get("t0"));
    assertEquals(List.of("SHIROBAKO"), request.variables().get("t1"));
    assertEquals(2, request.variables().size());
    assertTrue(request.query().contains("w0: searchWorks(titles: $t0, first: 50)"));
    assertTrue(request.query().contains("w1: searchWorks(titles: $t1, first: 50)"));
    assertTrue(request.query().contains("programs(first: 50"));
    assertTrue(request.query().contains("rebroadcast"));
    assertFalse(request.query().contains("w2:"));
    assertFalse(request.query().contains("pageInfo"));
    assertEquals(List.of("シャングリラ", "SHIROBAKO"), results.stream().map(r -> r.word()).toList());
  }

  @Test
  void mapsAliasesToWordsAndRemovesDuplicateWorksWithinWord() throws Exception {
    var stub = new Stub(data(String.join(",", work(1), work(2), work(1)), "", work(1)));

    var results = stub.search(List.of("A", "B", "C"), List.of(19));

    assertEquals(List.of(1, 2), results.get(0).works().stream().map(Anime::annictId).toList());
    assertTrue(results.get(1).works().isEmpty());
    assertEquals(List.of(1), results.get(2).works().stream().map(Anime::annictId).toList());
  }

  // ###########################################################################
  // MARK: 初回放送の判定

  @Test
  void choosesPriorityBeforeDateAndIgnoresRebroadcasts() throws Exception {
    var stub = new Stub(data(work(1,
        program(19, "2026-09-01T00:00:00Z", true),
        program(7, "2026-10-01T12:00:00Z"),
        program(19, "2026-10-02T16:00:00Z"),
        program(19, "2026-10-09T16:00:00Z"))));

    var first = only(stub, List.of(19, 7)).firstBroadcast();

    assertEquals(19, first.channelId());
    assertEquals("局19", first.channelName());
    assertEquals("2026-10-03T01:00+09:00[Asia/Tokyo]", first.startsAt().toString());
    assertEquals(DayOfWeek.SATURDAY, first.startsAt().getDayOfWeek());
  }

  @Test
  void fallsBackToLowerPriorityThenUnknownChannelButNeverToOtherChannels() throws Exception {
    var lower = new Stub(data(work(1, program(99, "2026-09-01T00:00:00Z"), program(7, "2026-10-01T00:00:00Z"))));
    assertEquals(7, only(lower, List.of(19, 7)).firstBroadcast().channelId());

    var unknown = new Stub(data(work(1, program(99, "2026-09-01T00:00:00Z"),
        "{\"startedAt\":\"2026-10-01T00:00:00Z\",\"rebroadcast\":false,\"channel\":null}")));
    var first = only(unknown, List.of(19)).firstBroadcast();
    assertEquals(0, first.channelId());
    assertNull(first.channelName());
    assertNotNull(first.startsAt());

    var other = only(new Stub(data(work(1, program(99, "2026-10-01T00:00:00Z")))), List.of(19, 7));
    assertNull(other.firstBroadcast());
    assertTrue(other.hasBroadcasts());
  }

  @Test
  void hasBroadcastsIsFalseWithoutProgramsOrWithOnlyRebroadcasts() throws Exception {
    var none = only(new Stub(data(work(1))), List.of(19));
    assertNull(none.firstBroadcast());
    assertFalse(none.hasBroadcasts());

    var rebroadcastOnly = only(new Stub(data(work(1, program(19, "2026-10-01T00:00:00Z", true)))), List.of(19));
    assertNull(rebroadcastOnly.firstBroadcast());
    assertFalse(rebroadcastOnly.hasBroadcasts());
  }

  @Test
  void keepsKnownStationWhenDateIsUnknownAndPrefersLaterDatedBroadcastOnSameStation() throws Exception {
    var unknownDate = only(new Stub(data(work(1, program(19, "invalid")))), List.of(19)).firstBroadcast();
    assertNull(unknownDate.startsAt());
    assertEquals("局19", unknownDate.channelName());

    var dated = only(new Stub(data(work(1, program(19, ""), program(19, "2026-10-01T00:00:00Z")))), List.of(19))
        .firstBroadcast();
    assertNotNull(dated.startsAt());
  }

  // ###########################################################################
  // MARK: 入力・応答の異常

  @Test
  void rejectsInvalidInputGraphQlErrorsAndBrokenResponses() throws Exception {
    var stub = new Stub(data(""));
    assertThrows(IllegalArgumentException.class, () -> stub.search(List.of(" "), List.of(19)));
    assertThrows(IllegalArgumentException.class, () -> stub.search(List.of("A"), List.of()));
    assertThrows(IllegalArgumentException.class, () -> stub.search(List.of("A"), List.of(0)));

    for(String broken : List.of(
        "{\"errors\":[{\"message\":\"denied\"}],\"data\":null}",
        "{\"data\":{}}",
        data("{\"annictId\":0,\"title\":\"不正\",\"programs\":{\"nodes\":[]}}"),
        data("{\"annictId\":1,\"title\":\"放送なし\"}"))) {
      assertThrows(AnimeSearchException.class, () -> new Stub(broken).search(List.of("A"), List.of(19)), broken);
    }
  }
}
