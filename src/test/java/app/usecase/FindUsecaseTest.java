package app.usecase;

import static org.junit.jupiter.api.Assertions.*;

import app.config.AnnictConfig;
import app.usecase.IAnnictClient.Anime;
import app.usecase.IAnnictClient.FirstBroadcast;
import app.usecase.IAnnictClient.WordResult;
import app.usecase.IInput.FindInput;
import app.usecase.IInput.FindInput.Format;
import app.usecase.IInput.FindInput.Status;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

class FindUsecaseTest {
  private static final String HEADER = "状態\t検索語\t放送開始日\t時刻\t曜日\t放送局\tタイトル\t公式URL\t理由";
  private static final String PLAIN_HEADER = "放送開始日\t時刻\t曜日\t放送局\tタイトル\t公式URL";
  private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-10-01T00:00:00+09:00");

  private final AnnictConfig config = new AnnictConfig() {
    public URI url() {
      return URI.create("https://example.com/graphql");
    }
    public String token() {
      return "test-token";
    }
    public List<Integer> channelPriority() {
      return List.of(19, 7);
    }
  };

  private static Anime anime(int id, String title, ZonedDateTime startsAt) {
    return new Anime(id, title, null, new FirstBroadcast(19, "TOKYO MX", startsAt), true);
  }

  private static ZonedDateTime jst(String dateTime) {
    return ZonedDateTime.parse(dateTime + "+09:00");
  }

  private String run(FindInput input, WordResult... results) {
    return new FindUsecase((words, priorities) -> List.of(results), config).run(input);
  }

  // ###########################################################################
  // MARK: 区分の判定

  @Test
  void classifiesWorksPerWordAndPassesWordsAndPriorities() {
    var calls = new ArrayList<List<String>>();
    IAnnictClient client = (words, priorities) -> {
      calls.add(words);
      assertEquals(List.of(19, 7), priorities);
      return List.of(
          new WordResult("ぼく", List.of(
              new Anime(1, "予定", URI.create("https://example.com/a"),
                  new FirstBroadcast(19, "TOKYO MX", jst("2026-10-03T01:00:00")), true),
              anime(2, "放送済み", jst("2004-04-01T18:00:00")),
              new Anime(3, "放送なし", null, null, false),
              new Anime(4, "優先局なし", null, null, true),
              anime(5, "日時なし", null))),
          new WordResult("わたし", List.of()));
    };

    String result = new FindUsecase(client, config).run(new FindInput(Format.TSV, List.of("ぼく", "わたし"), FROM));

    assertEquals(List.of(List.of("ぼく", "わたし")), calls);
    assertEquals(String.join("\n", HEADER,
        "放送予定\tぼく\t2026-10-03\t01:00\t土\tTOKYO MX\t予定\thttps://example.com/a\t",
        "対象外\tぼく\t2004-04-01\t18:00\t木\tTOKYO MX\t放送済み\t\t放送済み",
        "対象外\tぼく\t\t\t\t\t放送なし\t\t日時未定",
        "対象外\tぼく\t\t\t\t\t優先局なし\t\t対象局なし",
        "対象外\tぼく\t\t\t\tTOKYO MX\t日時なし\t\t日時未定",
        "未登録\tわたし\t\t\t\t\tわたし\t\t"), result);
  }

  @Test
  void fromIsInclusiveAndDefaultsToNow() {
    var exact = new WordResult("A", List.of(anime(1, "境界", FROM.toZonedDateTime())));
    assertTrue(run(new FindInput(Format.TSV, List.of("A"), FROM), exact).contains("放送予定\tA\t2026-10-01"));

    // 省略時は実行時の現在日時を下限にする。
    var now = ZonedDateTime.now();
    var works = new WordResult("A", List.of(anime(1, "未来", now.plusDays(1)), anime(2, "過去", now.minusDays(1))));
    var lines = run(new FindInput(Format.TSV, List.of("A")), works).lines().toList();
    assertTrue(lines.get(1).startsWith("放送予定\tA\t"), lines.get(1));
    assertTrue(lines.get(2).startsWith("対象外\tA\t") && lines.get(2).endsWith("放送済み"), lines.get(2));
  }

  @Test
  void switchesSelectStatusesAndPlainDropsStatusWordAndReason() {
    var results = new WordResult[]{
        new WordResult("ぼく", List.of(anime(1, "予定", jst("2026-10-05T23:00:00")),
            anime(2, "放送済み", jst("2004-04-01T18:00:00")))),
        new WordResult("わたし", List.of())};

    assertEquals(String.join("\n", HEADER, "未登録\tわたし\t\t\t\t\tわたし\t\t"),
        run(new FindInput(Format.TSV, List.of("ぼく", "わたし"), FROM, Set.of(Status.MISS), false), results));
    assertEquals(String.join("\n", PLAIN_HEADER,
        "2026-10-05\t23:00\t月\tTOKYO MX\t予定\t",
        "\t\t\t\tわたし\t"),
        run(new FindInput(Format.TSV, List.of("ぼく", "わたし"), FROM, Set.of(Status.HIT, Status.MISS), true),
            results));
    assertEquals(PLAIN_HEADER,
        run(new FindInput(Format.TSV, List.of("わたし"), FROM, Set.of(Status.HIT), true), results[1]));
  }

  // ###########################################################################
  // MARK: TSVへの整形

  @Test
  void sortsScheduledWorksByJapaneseWeekdayAndTimeIgnoringDateInBothFormats() throws Exception {
    var result = new WordResult("作品", List.of(
        anime(1, "日曜", jst("2026-10-04T12:00:00")),
        anime(2, "月曜夜", jst("2026-10-05T23:00:00")),
        anime(3, "月曜朝A", ZonedDateTime.parse("2026-10-18T16:00:00Z")),
        anime(4, "月曜朝B", jst("2026-10-12T01:00:00"))));
    var expected = List.of("月曜朝A", "月曜朝B", "月曜夜", "日曜");

    var tsv = run(new FindInput(Format.TSV, List.of("作品"), FROM), result);
    assertEquals(expected, tsv.lines().skip(1).map(row -> row.split("\t")[6]).toList());
    var markdown = decodeViewerUrl(run(new FindInput(Format.MD, List.of("作品"), FROM), result));
    assertEquals(expected, markdown.lines().filter(row -> row.startsWith("| 作品 | ["))
        .map(row -> row.substring("| 作品 | [".length(), row.indexOf("](https://annict.com/works/"))).toList());
  }

  @Test
  void formatsEveryWeekdayInJapaneseInTsvAndMarkdown() throws Exception {
    var monday = jst("2026-10-05T00:00:00");
    var works = new ArrayList<Anime>();
    for(int day = 0; day < 7; day++) {
      works.add(anime(day + 1, "作品" + day, monday.plusDays(day)));
    }
    var result = new WordResult("作品", works);

    var tsv = run(new FindInput(Format.TSV, List.of("作品"), FROM), result);
    assertEquals(List.of("月", "火", "水", "木", "金", "土", "日"),
        tsv.lines().skip(1).map(row -> row.split("\t")[4]).toList());
    var markdown = decodeViewerUrl(run(new FindInput(Format.MD, List.of("作品"), FROM), result));
    for(String day : List.of("月", "火", "水", "木", "金", "土", "日")) {
      assertTrue(markdown.contains("（" + day + "） 00:00"));
    }
  }

  @Test
  void keepsColumnsWhenExternalTextContainsTabsAndNewlines() {
    var result = new WordResult("A", List.of(new Anime(1, "作品\tA\r\n続編", null,
        new FirstBroadcast(19, "局\n名", jst("2026-10-01T12:00:00")), true)));

    assertEquals("放送予定\tA\t2026-10-01\t12:00\t木\t局 名\t作品 A 続編\t\t",
        run(new FindInput(Format.TSV, List.of("A"), FROM), result).lines().skip(1).findFirst().orElseThrow());
  }

  @Test
  void returnsHeaderWhenNothingMatches() {
    assertEquals(HEADER, run(new FindInput(Format.TSV, List.of("A"), FROM, Set.of(Status.HIT), false),
        new WordResult("A", List.of())));
  }

  // ###########################################################################
  // MARK: Markdown Viewerへの整形

  @Test
  void markdownViewerUrlDecodesToSectionsWithEscapedTextAndOfficialLinks() throws Exception {
    var results = new WordResult[]{
        new WordResult("A|B", List.of(
            new Anime(1, "作品*特別*\n続編", URI.create("https://example.com/a(b)?x=1&y=2"),
                new FirstBroadcast(19, "局|名", ZonedDateTime.parse("2026-10-02T16:00:00Z")), true),
            new Anime(2, "URLなし", null, null, false))),
        new WordResult("わたし", List.of())};

    String markdown = decodeViewerUrl(run(new FindInput(Format.MD, List.of("A|B", "わたし"), FROM), results));

    assertEquals(String.join("\n",
        "# アニメ検索結果", "",
        "**放送予定 1 件 · 対象外 1 件 · 未登録 1 件** · 日時は日本時間", "",
        "## 放送予定", "",
        "| 検索語 | 作品 | 初回放送 | 放送局 | 公式サイト |",
        "| :--- | :--- | :--- | :--- | :---: |",
        "| A\\|B | [作品\\*特別\\* 続編](https://annict.com/works/1) | 2026-10-03（土） 01:00 | 局\\|名 | [公式サイト](<https://example.com/a(b)?x=1&y=2>) |",
        "",
        "## 対象外", "",
        "| 検索語 | 作品 | 初回放送 | 放送局 | 理由 |",
        "| :--- | :--- | :--- | :--- | :--- |",
        "| A\\|B | [URLなし](https://annict.com/works/2) | — | — | 日時未定 |",
        "",
        "## 未登録", "",
        "- わたし"), markdown);
  }

  @Test
  void markdownShowsOnlySelectedSectionsAndPlainDropsWordAndReason() throws Exception {
    var result = new WordResult("A", List.of(anime(1, "放送済み", jst("2004-04-01T18:00:00"))));

    String markdown = decodeViewerUrl(run(new FindInput(Format.MD, List.of("A"), FROM,
        Set.of(Status.HIT, Status.SKIP), true), result));

    assertEquals(String.join("\n",
        "# アニメ検索結果", "",
        "**放送予定 0 件 · 対象外 1 件** · 日時は日本時間", "",
        "## 放送予定", "",
        "該当なし",
        "",
        "## 対象外", "",
        "| 作品 | 初回放送 | 放送局 |",
        "| :--- | :--- | :--- |",
        "| [放送済み](https://annict.com/works/1) | 2004-04-01（木） 18:00 | TOKYO MX |"), markdown);
  }

  @Test
  void oversizedMarkdownReturnsMessageInsteadOfTruncatedUrl() {
    var random = new Random(42);
    var title = new StringBuilder();
    for(int i = 0; i < 12000; i++)
      title.append((char) ('一' + random.nextInt(2000)));
    var result = new WordResult("A", List.of(anime(1, title.toString(), ZonedDateTime.now().plusDays(1))));

    assertTrue(run(new FindInput(Format.MD, List.of("A")), result).contains("検索範囲を絞ってください"));
  }

  private String decodeViewerUrl(String result) throws Exception {
    assertTrue(result.startsWith("https://kitware.github.io/markdown-viewer/?mdz="));
    String encoded = URI.create(result).getRawQuery().substring("mdz=".length());
    byte[] compressed = Base64.getDecoder().decode(URLDecoder.decode(encoded, StandardCharsets.UTF_8));
    try (var gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
      return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
