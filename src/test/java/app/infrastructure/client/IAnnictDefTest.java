package app.infrastructure.client;

import static org.junit.jupiter.api.Assertions.*;

import app.infrastructure.AnimeSearchException;
import app.usecase.IAnnictClient.Anime;
import app.usecase.IAnnictClient.WordResult;
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

  /** エイリアス名と作品の一覧の組（"w0", 作品…）から応答を作る。 */
  static String data(String... aliasAndWorks) {
    var fields = new ArrayList<String>();
    for(int i = 0; i < aliasAndWorks.length; i += 2) {
      fields.add("\"%s\":{\"nodes\":[%s]}".formatted(aliasAndWorks[i], aliasAndWorks[i + 1]));
    }
    return "{\"data\":{" + String.join(",", fields) + "}}";
  }

  static String work(int id, String... programs) {
    return """
        {"annictId":%d,"title":"作品%d","officialSiteUrl":null,"seasonYear":2027,"seasonName":"WINTER",
         "programs":{"nodes":[%s]}}
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

  private static List<WordResult> search(Stub stub, List<String> words, List<String> seasons) {
    return stub.search(words, seasons, List.of(19, 7));
  }

  private static Anime only(String work) throws Exception {
    return new Stub(data("w0", work)).search(List.of("テスト"), List.of(), List.of(19, 7))
        .getFirst().works().getFirst();
  }

  // ###########################################################################
  // MARK: 問い合わせ

  @Test
  void sendsEveryWordInOneRequestWithOneAliasPerWord() throws Exception {
    var stub = new Stub(data("w0", work(1), "w1", ""));

    var results = search(stub, List.of(" シャングリラ ", "SHIROBAKO", "シャングリラ", " "), List.of());

    assertEquals(1, stub.requests.size());
    var request = stub.requests.getFirst();
    assertEquals(List.of("シャングリラ"), request.variables().get("t0"));
    assertEquals(List.of("SHIROBAKO"), request.variables().get("t1"));
    assertEquals(2, request.variables().size());
    assertTrue(request.query().contains("w0: searchWorks(titles: $t0, first: 50)"));
    assertTrue(request.query().contains("w1: searchWorks(titles: $t1, first: 50)"));
    assertTrue(request.query().contains("seasonYear"));
    assertTrue(request.query().contains("rebroadcast"));
    assertFalse(request.query().contains("e0:"));
    assertFalse(request.query().contains("w2:"));
    assertEquals(List.of("シャングリラ", "SHIROBAKO"), results.stream().map(WordResult::word).toList());
    // シーズンを指定しない場合は、取得した作品の有無で存在を判定する。
    assertEquals(List.of(true, false), results.stream().map(WordResult::exists).toList());
  }

  @Test
  void wordsWithSeasonsAlsoCheckExistenceRegardlessOfSeason() throws Exception {
    var stub = new Stub(data("w0", "", "e0", "{\"annictId\":9}", "w1", "", "e1", ""));

    var results = search(stub, List.of("A", "B"), List.of("2027-winter", " 2027-winter"));

    var request = stub.requests.getFirst();
    assertEquals(List.of("2027-winter"), request.variables().get("s"));
    assertTrue(request.query().contains("w0: searchWorks(titles: $t0, seasons: $s, first: 50)"));
    assertTrue(request.query().contains("e0: searchWorks(titles: $t0, first: 1) { nodes { annictId } }"));
    assertTrue(request.query().contains("e1: searchWorks(titles: $t1, first: 1)"));
    assertEquals(List.of(true, false), results.stream().map(WordResult::exists).toList());
  }

  @Test
  void seasonsWithoutWordsListSeasonByWatchers() throws Exception {
    var stub = new Stub(data("w0", work(1)));

    var results = search(stub, List.of(), List.of("2026-autumn", "2027-winter"));

    var request = stub.requests.getFirst();
    assertEquals(List.of("2026-autumn", "2027-winter"), request.variables().get("s"));
    assertTrue(request.query().contains(
        "w0: searchWorks(seasons: $s, first: 50, orderBy: {field: WATCHERS_COUNT, direction: DESC})"));
    assertFalse(request.query().contains("$t0"));
    assertNull(results.getFirst().word());
    assertEquals(1, results.getFirst().works().size());
  }

  @Test
  void mapsAliasesToWordsAndRemovesDuplicateWorksWithinWord() throws Exception {
    var stub = new Stub(data("w0", String.join(",", work(1), work(2), work(1)), "w1", "", "w2", work(1)));

    var results = search(stub, List.of("A", "B", "C"), List.of());

    assertEquals(List.of(1, 2), results.get(0).works().stream().map(Anime::annictId).toList());
    assertTrue(results.get(1).works().isEmpty());
    assertEquals(List.of(1), results.get(2).works().stream().map(Anime::annictId).toList());
  }

  // ###########################################################################
  // MARK: シーズン・初回放送の判定

  @Test
  void formatsSeasonLikeAnnictAndKeepsMissingSeasonAsNull() throws Exception {
    assertEquals("2027-winter", only(work(1)).season());
    assertNull(only("{\"annictId\":1,\"title\":\"未定\",\"seasonYear\":null,\"seasonName\":null,"
        + "\"programs\":{\"nodes\":[]}}").season());
  }

  @Test
  void choosesPriorityBeforeDateAndIgnoresRebroadcasts() throws Exception {
    var anime = only(work(1,
        program(19, "2026-09-01T00:00:00Z", true),
        program(7, "2026-10-01T12:00:00Z"),
        program(19, "2026-10-02T16:00:00Z"),
        program(19, "2026-10-09T16:00:00Z")));

    var first = anime.firstBroadcast();
    assertEquals(19, first.channelId());
    assertEquals("局19", first.channelName());
    assertEquals("2026-10-03T01:00+09:00[Asia/Tokyo]", first.startsAt().toString());
    assertEquals(DayOfWeek.SATURDAY, first.startsAt().getDayOfWeek());
    // 局を問わない最初の放送日時も、再放送を除いて求める。
    assertEquals("2026-10-01T21:00+09:00[Asia/Tokyo]", anime.firstAiredAt().toString());
  }

  @Test
  void fallsBackToLowerPriorityThenUnknownChannelButNeverToOtherChannels() throws Exception {
    assertEquals(7, only(work(1, program(99, "2026-09-01T00:00:00Z"), program(7, "2026-10-01T00:00:00Z")))
        .firstBroadcast().channelId());

    var first = only(work(1, program(99, "2026-09-01T00:00:00Z"),
        "{\"startedAt\":\"2026-10-01T00:00:00Z\",\"rebroadcast\":false,\"channel\":null}")).firstBroadcast();
    assertEquals(0, first.channelId());
    assertNull(first.channelName());
    assertNotNull(first.startsAt());

    var other = only(work(1, program(99, "2026-10-01T00:00:00Z")));
    assertNull(other.firstBroadcast());
    assertNotNull(other.firstAiredAt());
    assertTrue(other.hasBroadcasts());
  }

  @Test
  void hasBroadcastsIsFalseWithoutProgramsOrWithOnlyRebroadcasts() throws Exception {
    var none = only(work(1));
    assertNull(none.firstBroadcast());
    assertNull(none.firstAiredAt());
    assertFalse(none.hasBroadcasts());

    var rebroadcastOnly = only(work(1, program(19, "2026-10-01T00:00:00Z", true)));
    assertNull(rebroadcastOnly.firstBroadcast());
    assertFalse(rebroadcastOnly.hasBroadcasts());
  }

  @Test
  void keepsKnownStationWhenDateIsUnknownAndPrefersLaterDatedBroadcastOnSameStation() throws Exception {
    var unknownDate = only(work(1, program(19, "invalid"))).firstBroadcast();
    assertNull(unknownDate.startsAt());
    assertEquals("局19", unknownDate.channelName());

    assertNotNull(only(work(1, program(19, ""), program(19, "2026-10-01T00:00:00Z"))).firstBroadcast().startsAt());
  }

  // ###########################################################################
  // MARK: 入力・応答の異常

  @Test
  void rejectsInvalidInputGraphQlErrorsAndBrokenResponses() throws Exception {
    var stub = new Stub(data("w0", ""));
    assertThrows(IllegalArgumentException.class, () -> stub.search(List.of(" "), List.of(" "), List.of(19)));
    assertThrows(IllegalArgumentException.class, () -> stub.search(List.of("A"), List.of(), List.of()));
    assertThrows(IllegalArgumentException.class, () -> stub.search(List.of("A"), List.of(), List.of(0)));

    for(String broken : List.of(
        "{\"errors\":[{\"message\":\"denied\"}],\"data\":null}",
        "{\"data\":{}}",
        data("w0", "{\"annictId\":0,\"title\":\"不正\",\"programs\":{\"nodes\":[]}}"),
        data("w0", "{\"annictId\":1,\"title\":\"放送なし\"}"))) {
      assertThrows(AnimeSearchException.class,
          () -> new Stub(broken).search(List.of("A"), List.of(), List.of(19)), broken);
    }
    // シーズン指定時に存在確認のエイリアスが欠けていれば異常とする。
    assertThrows(AnimeSearchException.class,
        () -> new Stub(data("w0", "")).search(List.of("A"), List.of("2027-winter"), List.of(19)));
  }
}
