package app.usecase;

import static org.junit.jupiter.api.Assertions.*;

import app.config.AnnictConfig;
import app.usecase.IAnnictClient.Anime;
import app.usecase.IAnnictClient.FirstBroadcast;
import app.usecase.IAnnictClient.WordResult;
import app.usecase.IInput.FindInput;
import app.usecase.IInput.FindInput.Format;
import app.usecase.IInput.FindInput.Period;
import app.usecase.IInput.FindInput.Status;
import app.usecase.Season.Name;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

class FindUsecaseTest {
  private static final String HEADER = "状態\t検索語\t放送開始日\t時刻\t曜日\t放送局\tタイトル\t公式URL";
  private static final String PLAIN_HEADER = "放送開始日\t時刻\t曜日\t放送局\tタイトル\t公式URL";
  /** 判定の基準にする現在日時（2026年秋）。 */
  private static final Clock CLOCK = Clock.fixed(OffsetDateTime.parse("2026-10-26T12:00:00+09:00").toInstant(),
      ZoneOffset.UTC);
  private static final Period WINTER_2027 = new Period.Seasons(List.of(new Season(2027, Name.WINTER)));
  private static final Set<Status> ALL = EnumSet.allOf(Status.class);

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

  /** 優先局（TOKYO MX）で startsAt に初回放送する作品。startsAt が null なら日時不明。 */
  private static Anime anime(int id, String title, String season, ZonedDateTime startsAt) {
    return new Anime(id, title, null, season, new FirstBroadcast(19, "TOKYO MX", startsAt), startsAt, true);
  }

  private static ZonedDateTime jst(String dateTime) {
    return ZonedDateTime.parse(dateTime + "+09:00");
  }

  private final List<List<String>> seasonCalls = new ArrayList<>();

  private String run(FindInput input, WordResult... results) {
    IAnnictClient client = (words, seasons, priorities) -> {
      seasonCalls.add(seasons);
      assertEquals(List.of(19, 7), priorities);
      return List.of(results);
    };
    return new FindUsecase(client, config, CLOCK).run(input);
  }

  // ###########################################################################
  // MARK: 期間を省略・日時で指定

  @Test
  void defaultsToScheduledWorksOfCurrentAndNextSeasons() {
    var result = run(new FindInput(Format.TSV, List.of("ぼく", "わたし")),
        new WordResult("ぼく", List.of(
            new Anime(1, "予定", URI.create("https://example.com/a"), "2026-autumn",
                new FirstBroadcast(19, "TOKYO MX", jst("2026-10-31T01:00:00")), jst("2026-10-31T01:00:00"), true),
            anime(2, "開始済み", "2026-autumn", jst("2026-10-05T23:00:00")),
            new Anime(3, "他局", null, "2027-winter", null, jst("2027-01-05T00:00:00"), true),
            anime(4, "来季", "2027-winter", null)), true),
        new WordResult("わたし", List.of(), false));

    // 期間は今季と来季。出力は放送予定だけで、放送済み・対象外・未登録は出さない。
    assertEquals(List.of(List.of("2026-autumn", "2027-winter")), seasonCalls);
    assertEquals(String.join("\n", HEADER,
        "放送予定\tぼく\t2026-10-31\t01:00\t土\tTOKYO MX\t予定\thttps://example.com/a",
        "放送予定\tぼく\t\t\t\tTOKYO MX\t来季\t"), result);
  }

  @Test
  void rangeSeparatesOtherChannelsOutOfPeriodAndMissingWords() {
    var range = new Period.Range(OffsetDateTime.parse("2026-10-26T00:00:00+09:00"), null);

    var result = run(new FindInput(Format.TSV, List.of("ぼく", "ワンパンマン", "わたし"), range, ALL, false),
        new WordResult("ぼく", List.of(
            anime(1, "予定", "2026-autumn", jst("2026-10-31T01:00:00")),
            new Anime(3, "他局", null, "2026-autumn", null, jst("2026-11-01T00:00:00"), true),
            anime(4, "日時なし", "2027-winter", null)), true),
        new WordResult("ワンパンマン", List.of(anime(5, "ワンパンマン", "2015-autumn", jst("2015-10-05T01:05:00"))), true),
        new WordResult("わたし", List.of(), false));

    assertEquals(List.of(List.of()), seasonCalls);
    assertEquals(String.join("\n", HEADER,
        "放送予定\tぼく\t2026-10-31\t01:00\t土\tTOKYO MX\t予定\t",
        "対象外\tぼく\t\t\t\t\t他局\t",
        "期間外\tワンパンマン\t\t\t\t\tワンパンマン\t",
        "未登録\tわたし\t\t\t\t\tわたし\t"), result);
  }
  @Test
  void rangeIncludesFromExcludesToAndSplitsScheduledAndAiredByNow() {
    var range = new Period.Range(OffsetDateTime.parse("2026-10-01T00:00:00+09:00"),
        OffsetDateTime.parse("2027-01-01T00:00:00+09:00"));

    var result = run(new FindInput(Format.TSV, List.of("A"), range, ALL, false), new WordResult("A", List.of(
        anime(1, "境界", "2026-autumn", jst("2026-10-01T00:00:00")),
        anime(2, "未来", "2026-autumn", jst("2026-12-31T23:59:00")),
        anime(3, "終端", "2027-winter", jst("2027-01-01T00:00:00")),
        anime(4, "以前", "2026-summer", jst("2026-09-30T23:59:00"))), true));

    assertEquals(String.join("\n", HEADER,
        "放送予定\tA\t2026-12-31\t23:59\t木\tTOKYO MX\t未来\t",
        "放送済み\tA\t2026-10-01\t00:00\t木\tTOKYO MX\t境界\t"), result);
  }

  @Test
  void periodOnlyRangeQueriesNeighbouringSeasonsAndLeavesWordEmpty() {
    var range = new Period.Range(OffsetDateTime.parse("2027-01-01T00:00:00+09:00"),
        OffsetDateTime.parse("2027-04-01T00:00:00+09:00"));

    var result = run(new FindInput(Format.TSV, List.of(), range), new WordResult(null, List.of(
        anime(1, "年末開始", "2027-winter", jst("2026-12-28T00:00:00")),
        anime(2, "冬", "2027-winter", jst("2027-01-10T23:00:00"))), true));

    assertEquals(List.of(List.of("2026-autumn", "2027-winter", "2027-spring")), seasonCalls);
    assertEquals(String.join("\n", HEADER, "放送予定\t\t2027-01-10\t23:00\t日\tTOKYO MX\t冬\t"), result);

    // to がなければ、現在の来季まで問い合わせる。from がなければ問い合わせるシーズンを決められない。
    seasonCalls.clear();
    run(new FindInput(Format.TSV, List.of(), new Period.Range(OffsetDateTime.parse("2026-10-26T00:00:00+09:00"),
        null)), new WordResult(null, List.of(), false));
    assertEquals(List.of(List.of("2026-summer", "2026-autumn", "2027-winter")), seasonCalls);
    assertThrows(IllegalArgumentException.class, () -> run(new FindInput(Format.TSV, List.of(),
        new Period.Range(null, OffsetDateTime.parse("2027-04-01T00:00:00+09:00")))));
  }

  // ###########################################################################
  // MARK: 期間をシーズンで指定

  @Test
  void seasonPeriodUsesWorkSeasonAndTreatsUnknownDatesAsScheduled() {
    var result = run(new FindInput(Format.TSV, List.of("SAKAMOTO", "ワンパンマン"), WINTER_2027, ALL, false),
        new WordResult("SAKAMOTO", List.of(
            new Anime(1, "放送データなし", null, "2027-winter", null, null, false),
            anime(2, "年末開始", "2027-winter", jst("2026-12-28T00:00:00")),
            new Anime(3, "他局のみ", null, "2027-winter", null, jst("2027-01-05T00:00:00"), true),
            anime(4, "日時なし", "2027-winter", null),
            anime(5, "別シーズン", "2025-winter", jst("2025-01-11T23:00:00"))), true),
        new WordResult("ワンパンマン", List.of(), true));

    assertEquals(List.of(List.of("2027-winter")), seasonCalls);
    assertEquals(String.join("\n", HEADER,
        "放送予定\tSAKAMOTO\t2026-12-28\t00:00\t月\tTOKYO MX\t年末開始\t",
        "放送予定\tSAKAMOTO\t\t\t\t\t放送データなし\t",
        "放送予定\tSAKAMOTO\t\t\t\tTOKYO MX\t日時なし\t",
        "対象外\tSAKAMOTO\t\t\t\t\t他局のみ\t",
        "期間外\tワンパンマン\t\t\t\t\tワンパンマン\t"), result);
  }

  @Test
  void seasonPeriodMarksAiredWorksOfCurrentSeason() {
    var autumn = new Period.Seasons(List.of(new Season(2026, Name.AUTUMN)));
    var result = run(new FindInput(Format.TSV, List.of("A"), autumn, ALL, false), new WordResult("A", List.of(
        anime(1, "開始済み", "2026-autumn", jst("2026-10-05T23:00:00"))), true));
    assertEquals(String.join("\n", HEADER, "放送済み\tA\t2026-10-05\t23:00\t月\tTOKYO MX\t開始済み\t"), result);
  }

  // ###########################################################################
  // MARK: スイッチ・TSVへの整形

  @Test
  void switchesSelectStatusesAndPlainDropsStatusAndWord() {
    var results = new WordResult[]{
        new WordResult("ぼく", List.of(anime(1, "予定", "2026-autumn", jst("2026-11-02T23:00:00"))), true),
        new WordResult("わたし", List.of(), false)};

    assertEquals(String.join("\n", HEADER, "未登録\tわたし\t\t\t\t\tわたし\t"),
        run(new FindInput(Format.TSV, List.of("ぼく", "わたし"), null, Set.of(Status.MISS), false), results));
    assertEquals(String.join("\n", PLAIN_HEADER, "2026-11-02\t23:00\t月\tTOKYO MX\t予定\t", "\t\t\t\tわたし\t"),
        run(new FindInput(Format.TSV, List.of("ぼく", "わたし"), null, Set.of(Status.HIT, Status.MISS), true),
            results));
    assertEquals(PLAIN_HEADER, run(new FindInput(Format.TSV, List.of("わたし"), null, Set.of(), true), results[1]));
  }

  @Test
  void sortsByJapaneseWeekdayAndTimeIgnoringDateInBothFormats() throws Exception {
    var result = new WordResult("作品", List.of(
        anime(1, "日曜", "2027-winter", jst("2027-01-03T12:00:00")),
        anime(2, "月曜夜", "2027-winter", jst("2027-01-04T23:00:00")),
        anime(3, "月曜朝A", "2027-winter", ZonedDateTime.parse("2027-01-17T16:00:00Z")),
        anime(4, "月曜朝B", "2027-winter", jst("2027-01-11T01:00:00"))), true);
    var expected = List.of("月曜朝A", "月曜朝B", "月曜夜", "日曜");

    var tsv = run(new FindInput(Format.TSV, List.of("作品"), WINTER_2027), result);
    assertEquals(expected, tsv.lines().skip(1).map(row -> row.split("\t")[6]).toList());
    var markdown = decodeViewerUrl(run(new FindInput(Format.MD, List.of("作品"), WINTER_2027), result));
    assertEquals(expected, markdown.lines().filter(row -> row.startsWith("| 作品 | ["))
        .map(row -> row.substring("| 作品 | [".length(), row.indexOf("](https://annict.com/works/"))).toList());
  }

  @Test
  void formatsEveryWeekdayInJapaneseInTsvAndMarkdown() throws Exception {
    var monday = jst("2027-01-04T00:00:00");
    var works = new ArrayList<Anime>();
    for(int day = 0; day < 7; day++) {
      works.add(anime(day + 1, "作品" + day, "2027-winter", monday.plusDays(day)));
    }
    var result = new WordResult("作品", works, true);

    var tsv = run(new FindInput(Format.TSV, List.of("作品"), WINTER_2027), result);
    assertEquals(List.of("月", "火", "水", "木", "金", "土", "日"),
        tsv.lines().skip(1).map(row -> row.split("\t")[4]).toList());
    var markdown = decodeViewerUrl(run(new FindInput(Format.MD, List.of("作品"), WINTER_2027), result));
    for(String day : List.of("月", "火", "水", "木", "金", "土", "日")) {
      assertTrue(markdown.contains("（" + day + "） 00:00"));
    }
  }

  @Test
  void keepsColumnsWhenExternalTextContainsTabsAndNewlines() {
    var result = new WordResult("A", List.of(new Anime(1, "作品\tA\r\n続編", null, "2027-winter",
        new FirstBroadcast(19, "局\n名", jst("2027-01-04T12:00:00")), null, true)), true);

    assertEquals("放送予定\tA\t2027-01-04\t12:00\t月\t局 名\t作品 A 続編\t",
        run(new FindInput(Format.TSV, List.of("A"), WINTER_2027), result).lines().skip(1).findFirst().orElseThrow());
  }

  // ###########################################################################
  // MARK: Markdown Viewerへの整形

  @Test
  void markdownViewerUrlDecodesToSectionsWithEscapedTextAndOfficialLinks() throws Exception {
    var results = new WordResult[]{
        new WordResult("A|B", List.of(
            new Anime(1, "作品*特別*\n続編", URI.create("https://example.com/a(b)?x=1&y=2"), "2027-winter",
                new FirstBroadcast(19, "局|名", ZonedDateTime.parse("2027-01-08T16:00:00Z")), null, true),
            new Anime(2, "他局", null, "2027-winter", null, null, true)), true),
        new WordResult("ワンパンマン", List.of(), true),
        new WordResult("わたし", List.of(), false)};

    String markdown = decodeViewerUrl(run(new FindInput(Format.MD, List.of("A|B", "ワンパンマン", "わたし"),
        WINTER_2027, ALL, false), results));

    assertEquals(String.join("\n",
        "# アニメ検索結果", "",
        "**放送予定 1 件 · 放送済み 0 件 · 対象外 1 件 · 期間外 1 件 · 未登録 1 件** · 日時は日本時間", "",
        "## 放送予定", "",
        "| 検索語 | 作品 | 初回放送 | 放送局 | 公式サイト |",
        "| :--- | :--- | :--- | :--- | :---: |",
        "| A\\|B | [作品\\*特別\\* 続編](https://annict.com/works/1) | 2027-01-09（土） 01:00 | 局\\|名 | [公式サイト](<https://example.com/a(b)?x=1&y=2>) |",
        "",
        "## 放送済み", "",
        "該当なし",
        "",
        "## 対象外", "",
        "| 検索語 | 作品 | 初回放送 | 放送局 |",
        "| :--- | :--- | :--- | :--- |",
        "| A\\|B | [他局](https://annict.com/works/2) | — | — |",
        "",
        "## 期間外", "",
        "- ワンパンマン",
        "",
        "## 未登録", "",
        "- わたし"), markdown);
  }

  @Test
  void markdownShowsOnlySelectedSectionsAndPlainDropsWord() throws Exception {
    var result = new WordResult("A", List.of(anime(1, "予定", "2027-winter", jst("2027-01-04T18:00:00"))), true);

    String markdown = decodeViewerUrl(run(new FindInput(Format.MD, List.of("A"), WINTER_2027,
        Set.of(Status.HIT), true), result));

    assertEquals(String.join("\n",
        "# アニメ検索結果", "",
        "**放送予定 1 件** · 日時は日本時間", "",
        "## 放送予定", "",
        "| 作品 | 初回放送 | 放送局 | 公式サイト |",
        "| :--- | :--- | :--- | :---: |",
        "| [予定](https://annict.com/works/1) | 2027-01-04（月） 18:00 | TOKYO MX | — |"), markdown);
  }

  @Test
  void oversizedMarkdownReturnsMessageInsteadOfTruncatedUrl() {
    var random = new Random(42);
    var title = new StringBuilder();
    for(int i = 0; i < 12000; i++)
      title.append((char) ('一' + random.nextInt(2000)));
    var result = new WordResult("A", List.of(anime(1, title.toString(), "2027-winter", null)), true);

    assertTrue(run(new FindInput(Format.MD, List.of("A"), WINTER_2027), result).contains("検索範囲を絞ってください"));
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
