package app.usecase;

import static org.junit.jupiter.api.Assertions.*;

import app.config.AnnictConfig;
import app.usecase.IAnnictClient.Anime;
import app.usecase.IAnnictClient.FirstBroadcast;
import app.usecase.IAnnictClient.SearchCondition;
import app.usecase.IInput.FindInput;
import app.usecase.IInput.FindInput.Format;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.time.ZonedDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FindUsecaseTest {
  private final AnnictConfig config = new AnnictConfig() {
    public URI url() { return URI.create("https://example.com/graphql"); }
    public String token() { return "test-token"; }
    public List<Integer> channelPriority() { return List.of(19, 7); }
  };

  @Test
  void searchesEveryTitleAndReturnsUniqueWorksWithJapaneseDates() {
    var calls = new ArrayList<SearchCondition>();
    var broadcast = new FirstBroadcast(19, "TOKYO MX",
      ZonedDateTime.parse("2026-10-02T16:00:00Z"));
    var shared = new Anime(1, "作品A", URI.create("https://example.com/anime"), broadcast);

    IAnnictClient client = (condition, priorities) -> {
      calls.add(condition);
      assertEquals(List.of(19, 7), priorities);
      return (condition.title().equals("A")
        ? List.of(shared, new Anime(2, "劇場版A", null, null))
        : List.of(shared, new Anime(3, "作品B", null, broadcast)));
    };

    var from = OffsetDateTime.parse("2026-10-01T00:00:00+09:00");
    String result = new FindUsecase(client, config).run(new FindInput(Format.TSV, List.of("A", "B", " A "), from));

    assertEquals(List.of(
      new SearchCondition("A", List.of(), from, false),
      new SearchCondition("B", List.of(), from, false)), calls);
    assertEquals(String.join("\n",
      "放送開始日\t時刻\t曜日\t放送局\tタイトル\t公式URL",
      "2026-10-03\t01:00\t土\tTOKYO MX\t作品A\thttps://example.com/anime",
      "2026-10-03\t01:00\t土\tTOKYO MX\t作品B\t"), result);
  }

  @Test
  void defaultsToOneCurrentInstantForAllTitles() {
    var calls = new ArrayList<SearchCondition>();
    IAnnictClient client = (condition, priorities) -> {
      calls.add(condition);
      return List.of();
    };
    var before = OffsetDateTime.now();
    new FindUsecase(client, config).run(new FindInput(Format.TSV, List.of("A", "B")));
    var after = OffsetDateTime.now();
    assertEquals(2, calls.size());
    var from = calls.getFirst().minimumStartsAt();
    assertFalse(from.isBefore(before));
    assertFalse(from.isAfter(after));
    assertEquals(from, calls.get(1).minimumStartsAt());
  }

  @Test
  void excludesIncompleteBroadcasts() {
    IAnnictClient client = (condition, priorities) -> List.of(
      new Anime(1, "日時未定", null, new FirstBroadcast(19, "局", null)),
      new Anime(2, "局未定", null, new FirstBroadcast(19, " ", ZonedDateTime.now())),
      new Anime(3, "局なし", null, new FirstBroadcast(19, null, ZonedDateTime.now())));
    assertEquals("放送開始日\t時刻\t曜日\t放送局\tタイトル\t公式URL",
      new FindUsecase(client, config).run(new FindInput(Format.TSV, List.of("A"))));
  }

  @Test
  void keepsSixColumnsWhenExternalTextContainsTabsAndNewlines() {
    IAnnictClient client = (condition, priorities) -> List.of(new Anime(1,
      "作品\tA\r\n続編", null, new FirstBroadcast(19, "局\n名",
      ZonedDateTime.parse("2026-10-01T12:00:00+09:00"))));

    String result = new FindUsecase(client, config).run(new FindInput(Format.TSV, List.of("A")));

    assertEquals(2, result.lines().count());
    assertEquals("2026-10-01\t12:00\t木\t局 名\t作品 A 続編\t", result.lines().skip(1).findFirst().orElseThrow());
  }

  @Test
  void markdownViewerUrlDecodesToTableWithEscapedTextAndOfficialLinks() throws Exception {
    var broadcast = new FirstBroadcast(19, "局|名", ZonedDateTime.parse("2026-10-02T16:00:00Z"));
    IAnnictClient client = (condition, priorities) -> List.of(
      new Anime(1, "作品*特別*\n続編", URI.create("https://example.com/a(b)?x=1&y=2"), broadcast),
      new Anime(2, "URLなし", null, broadcast));
    String result = new FindUsecase(client, config).run(new FindInput(Format.MD, List.of("A")));
    String markdown = decodeViewerUrl(result);
    assertEquals(String.join("\n",
      "| 放送開始日 | 時刻 | 曜日 | 放送局 | タイトル | 公式URL |",
      "| --- | --- | --- | --- | --- | --- |",
      "| 2026-10-03 | 01:00 | 土 | 局\\|名 | 作品\\*特別\\* 続編 | [公式サイト](<https://example.com/a(b)?x=1&y=2>) |",
      "| 2026-10-03 | 01:00 | 土 | 局\\|名 | URLなし |  |"), markdown);
  }

  @Test
  void markdownReportsNoMatches() throws Exception {
    var usecase = new FindUsecase((condition, priorities) -> List.of(), config);
    assertTrue(decodeViewerUrl(usecase.run(new FindInput(Format.MD, List.of("A"))))
      .endsWith("\n\n該当する作品はありません。"));
  }

  @Test
  void oversizedMarkdownReturnsMessageInsteadOfTruncatedUrl() {
    var random = new java.util.Random(42);
    var title = new StringBuilder();
    for (int i = 0; i < 12000; i++) title.append((char) ('一' + random.nextInt(2000)));
    IAnnictClient client = (condition, priorities) -> List.of(new Anime(1, title.toString(), null,
      new FirstBroadcast(19, "局", ZonedDateTime.now())));
    String result = new FindUsecase(client, config).run(new FindInput(Format.MD, List.of("A")));
    assertTrue(result.contains("検索範囲を絞ってください"));
  }

  private String decodeViewerUrl(String result) throws Exception {
    assertTrue(result.startsWith("https://kitware.github.io/markdown-viewer/?mdz="));
    String encoded = URI.create(result).getRawQuery().substring("mdz=".length());
    byte[] compressed = Base64.getDecoder().decode(URLDecoder.decode(encoded, StandardCharsets.UTF_8));
    try (var gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
      return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void returnsHeaderWhenNoWorksMatch() {
    var usecase = new FindUsecase((condition, priorities) -> List.of(), config);
    assertEquals("放送開始日\t時刻\t曜日\t放送局\tタイトル\t公式URL",
      usecase.run(new FindInput(Format.TSV, List.of("該当なし"))));
  }
}
