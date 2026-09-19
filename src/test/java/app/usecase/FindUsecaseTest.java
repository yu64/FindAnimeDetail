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
      return List.of(shared, new Anime(2, "劇場版A", null, null), shared,
        new Anime(3, "作品B", null, broadcast));
    };

    var from = OffsetDateTime.parse("2026-10-01T00:00:00+09:00");
    String result = new FindUsecase(client, config).run(new FindInput(Format.TSV, List.of("A", "B", " A "), from));

    assertEquals(List.of(
      new SearchCondition(List.of("A", "B"), List.of(), from, false)), calls);
    assertEquals(String.join("\n",
      "放送開始日\t時刻\t曜日\t放送局\tタイトル\t公式URL",
      "2026-10-03\t01:00\t土\tTOKYO MX\t作品A\thttps://example.com/anime",
      "2026-10-03\t01:00\t土\tTOKYO MX\t作品B\t",
      "Unknow\tUnknow\tUnknow\tUnknow\t劇場版A\t"), result);
  }

  @Test
  void sortsByJapaneseWeekdayAndTimeIgnoringDateInBothFormats() throws Exception {
    IAnnictClient client = (condition, priorities) -> List.of(
      new Anime(1, "不明A", null, null),
      new Anime(2, "日曜", null, new FirstBroadcast(19, "局", ZonedDateTime.parse("2026-10-04T12:00:00+09:00"))),
      new Anime(3, "月曜夜", null, new FirstBroadcast(19, "局", ZonedDateTime.parse("2026-10-05T23:00:00+09:00"))),
      new Anime(4, "月曜朝A", null, new FirstBroadcast(19, "局", ZonedDateTime.parse("2026-10-18T16:00:00Z"))),
      new Anime(5, "月曜朝B", null, new FirstBroadcast(19, "局", ZonedDateTime.parse("2026-10-12T01:00:00+09:00"))),
      new Anime(6, "不明B", null, new FirstBroadcast(19, "局", null)));
    var usecase = new FindUsecase(client, config);
    var expected = List.of("月曜朝A", "月曜朝B", "月曜夜", "日曜", "不明A", "不明B");
    var tsv = usecase.run(new FindInput(Format.TSV, List.of("作品")));
    assertEquals(expected, tsv.lines().skip(1).map(row -> row.split("\t")[4]).toList());
    var markdown = decodeViewerUrl(usecase.run(new FindInput(Format.MD, List.of("作品"))));
    assertEquals(expected, markdown.lines().filter(row -> row.startsWith("| ["))
      .map(row -> row.substring(3, row.indexOf("](https://annict.com/works/"))).toList());
    var complete = usecase.run(new FindInput(Format.TSV, List.of("作品"), null, true));
    assertEquals(expected.subList(0, 4), complete.lines().skip(1).map(row -> row.split("\t")[4]).toList());
  }

  @Test
  void formatsEveryWeekdayInJapaneseInTsvAndMarkdown() throws Exception {
    var monday = ZonedDateTime.parse("2026-10-05T00:00:00+09:00");
    var works = new ArrayList<Anime>();
    for (int day = 0; day < 7; day++) {
      works.add(new Anime(day + 1, "作品" + day, null,
        new FirstBroadcast(19, "TOKYO MX", monday.plusDays(day))));
    }
    var usecase = new FindUsecase((condition, priorities) -> works, config);
    var tsv = usecase.run(new FindInput(Format.TSV, List.of("作品")));
    assertEquals(List.of("月", "火", "水", "木", "金", "土", "日"),
      tsv.lines().skip(1).map(row -> row.split("\t")[2]).toList());
    var markdown = decodeViewerUrl(usecase.run(new FindInput(Format.MD, List.of("作品"))));
    for (String day : List.of("月", "火", "水", "木", "金", "土", "日")) {
      assertTrue(markdown.contains("（" + day + "） 00:00"));
    }
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
    assertEquals(1, calls.size());
    var from = calls.getFirst().minimumStartsAt();
    assertFalse(from.isBefore(before));
    assertFalse(from.isAfter(after));
    assertEquals(List.of("A", "B"), calls.getFirst().titles());
  }

  @Test
  void completeFiltersMissingFieldsInBothFormatsButKeepsMissingOfficialUrl() throws Exception {
    var date = ZonedDateTime.parse("2026-10-01T12:00:00+09:00");
    IAnnictClient client = (condition, priorities) -> List.of(
      new Anime(1, "詳細あり", null, new FirstBroadcast(19, "TOKYO MX", date)),
      new Anime(2, "放送なし", null, null),
      new Anime(3, "日時なし", null, new FirstBroadcast(19, "TOKYO MX", null)),
      new Anime(4, "局なし", null, new FirstBroadcast(0, null, date)),
      new Anime(5, "局空白", null, new FirstBroadcast(19, " ", date)));
    var usecase = new FindUsecase(client, config);
    var tsv = usecase.run(new FindInput(Format.TSV, List.of("作品"), null, true));
    assertEquals(2, tsv.lines().count());
    assertTrue(tsv.endsWith("2026-10-01\t12:00\t木\tTOKYO MX\t詳細あり\t"));
    var markdown = decodeViewerUrl(usecase.run(new FindInput(Format.MD, List.of("作品"), null, true)));
    assertTrue(markdown.contains("**1 作品**"));
    assertTrue(markdown.contains("[詳細あり](https://annict.com/works/1)"));
    for (String excluded : List.of("放送なし", "日時なし", "局なし", "局空白")) {
      assertFalse(markdown.contains(excluded));
    }
    assertEquals(6, usecase.run(new FindInput(Format.TSV, List.of("作品"))).lines().count());
  }

  @Test
  void completeReportsNoMatchesWhenAllBroadcastsAreUnknown() throws Exception {
    var usecase = new FindUsecase((condition, priorities) -> List.of(new Anime(1, "不明", null, null)), config);
    assertEquals("放送開始日\t時刻\t曜日\t放送局\tタイトル\t公式URL",
      usecase.run(new FindInput(Format.TSV, List.of("作品"), null, true)));
    assertTrue(decodeViewerUrl(usecase.run(new FindInput(Format.MD, List.of("作品"), null, true)))
      .endsWith("該当する作品はありません。"));
  }

  @Test
  void preservesKnownFieldsAndLabelsMissingFields() {
    IAnnictClient client = (condition, priorities) -> List.of(
      new Anime(1, "日時未定", null, new FirstBroadcast(19, "局", null)),
      new Anime(2, "局未定", null, new FirstBroadcast(19, " ", ZonedDateTime.parse("2026-10-01T12:00:00+09:00"))),
      new Anime(3, "局なし", null, null));
    assertEquals(String.join("\n", "放送開始日\t時刻\t曜日\t放送局\tタイトル\t公式URL",
      "2026-10-01\t12:00\t木\tUnknow\t局未定\t",
      "Unknow\tUnknow\tUnknow\t局\t日時未定\t",
      "Unknow\tUnknow\tUnknow\tUnknow\t局なし\t"),
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
      new Anime(2, "URLなし", null, null));
    String result = new FindUsecase(client, config).run(new FindInput(Format.MD, List.of("A")));
    String markdown = decodeViewerUrl(result);
    assertEquals(String.join("\n",
      "# アニメ検索結果", "",
      "**2 作品** · 日時は日本時間", "",
      "> Unknow：作品の存在は確認できましたが、該当する放送情報は不明です。", "",
      "| 作品 | 初回放送 | 放送局 | 公式サイト |",
      "| :--- | :--- | :--- | :---: |",
      "| [作品\\*特別\\* 続編](https://annict.com/works/1) | 2026-10-03（土） 01:00 | 局\\|名 | [公式サイト](<https://example.com/a(b)?x=1&y=2>) |",
      "| [URLなし](https://annict.com/works/2) | Unknow | Unknow | — |"), markdown);
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
