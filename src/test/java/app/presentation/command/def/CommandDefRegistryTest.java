package app.presentation.command.def;

import static org.junit.jupiter.api.Assertions.*;

import app.infrastructure.MugCommandSyntaxParser;
import app.presentation.command.parse.CommandElementReader;
import app.usecase.IInput;
import app.usecase.IInput.FindInput;
import app.usecase.IInput.FindInput.Format;
import app.usecase.IInput.FindInput.Period;
import app.usecase.IInput.FindInput.Status;
import app.usecase.IInput.HelpInput;
import app.usecase.IInput.ReserveInput;
import app.usecase.IInput.ReserveInput.Entry;
import app.usecase.Season;
import app.util.IResult;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CommandDefRegistryTest {

  private final CommandDefRegistry registry = new CommandDefRegistry(
      List.of(new HelpCommandDef(), new FindCommandDef(), new ReserveCommandDef()));

  @Test
  void mapsHelpAndFindWithDefaultOrExplicitFormat() {
    assertEquals(IResult.ok(new HelpInput("")), map("/help"));
    assertEquals(IResult.ok(new FindInput(Format.TSV, List.of("anime"))),
        map("/find #word anime"));
    assertEquals(IResult.ok(new FindInput(Format.MD, List.of("a", "b"))),
        map("/find #format md #word a, b"));
    assertEquals(IResult.ok(new FindInput(Format.TSV, List.of("a", "b"))),
        map("/find tsv a, b"));
  }

  @Test
  void mapsStatusAndPlainSwitchesWithOtherOptionsAndRejectsValuesOrDuplicates() {
    assertEquals(IResult.ok(new FindInput(Format.TSV, List.of("anime"), range(null), Set.of(Status.MISS), false)),
        map("/find #word anime #miss"));
    var from = OffsetDateTime.parse("2026-10-01T00:00:00+09:00");
    assertEquals(IResult.ok(new FindInput(Format.MD, List.of("a", "b"), range(from),
        Set.of(Status.HIT, Status.DONE, Status.SKIP, Status.OUT), true)),
        map("/find #skip #format md #word a, b #hit #done #out #plain #from 2026-10-01T00:00"));
    // 状態の指定がなければすべての状態になる。
    assertEquals(EnumSet.allOf(Status.class),
        ((FindInput) map("/find #word anime #plain").ok().val()).statuses());
    for(String suffix : List.of("#hit true", "#plain false", "#miss #miss", "#done #done")) {
      assertInstanceOf(IResult.Err.class, map("/find #word anime " + suffix), suffix);
    }
  }

  @Test
  void mapsFromWithJapaneseTimeOrExplicitOffset() {
    var from = OffsetDateTime.parse("2026-10-01T00:00:00+09:00");
    assertEquals(IResult.ok(new FindInput(Format.TSV, List.of("anime"), range(from))),
        map("/find #word anime #from 2026-10-01T00:00"));
    assertEquals(IResult.ok(new FindInput(Format.TSV, List.of("anime"), range(from))),
        map("/find #from 2026-10-01T00:00:00+09:00 #word anime"));
    var utc = OffsetDateTime.parse("2026-09-30T15:00:00Z");
    assertEquals(IResult.ok(new FindInput(Format.TSV, List.of("anime"), range(utc))),
        map("/find #word anime #from 2026-09-30T15:00:00Z"));
    assertEquals(IResult.ok(new FindInput(Format.TSV, List.of("a", "b"), range(from))),
        map("/find #from 2026-10-01T00:00\na\nb"));
  }

  @Test
  void mapsFromWithoutTimeOrDayToJapaneseMidnight() {
    for(String value : List.of("2026-10", "2026-10-01")) {
      assertEquals(IResult.ok(new FindInput(Format.TSV, List.of("anime"),
          range(OffsetDateTime.parse("2026-10-01T00:00:00+09:00")))),
          map("/find #word anime #from " + value));
    }
    assertEquals(IResult.ok(new FindInput(Format.MD, List.of("a", "b"),
        range(OffsetDateTime.parse("2028-02-29T00:00:00+09:00")), Set.of(Status.HIT), false)),
        map("/find #format md #word a, b #from 2028-02-29 #hit"));
  }

  @Test
  void mapsToAndPeriodOnlyRangeAndRejectsReversedRange() {
    var from = OffsetDateTime.parse("2027-01-01T00:00:00+09:00");
    var to = OffsetDateTime.parse("2027-04-01T00:00:00+09:00");
    assertEquals(IResult.ok(new FindInput(Format.TSV, List.of("a"), new Period.Range(from, to))),
        map("/find #word a #from 2027-01 #to 2027-04"));
    // 期間を指定すれば検索語は省略できる。
    assertEquals(IResult.ok(new FindInput(Format.TSV, List.of(), new Period.Range(null, to))),
        map("/find #to 2027-04"));
    assertEquals(IResult.err("from は to より前を指定してください"), map("/find #from 2027-04 #to 2027-04"));
    assertInstanceOf(IResult.Err.class, map("/find #word a #to 2027-13"));
  }

  @Test
  void mapsSeasonLabelsAndRelativeSeasonsAndRejectsMixedOrInvalidPeriods() {
    var today = LocalDate.now(ZoneId.of("Asia/Tokyo"));
    assertEquals(IResult.ok(new FindInput(Format.TSV, List.of(), new Period.Seasons(List.of(
        new Season(2027, Season.Name.WINTER), Season.of(today), Season.of(today).next())))),
        map("/find #season 2027-winter, now, next"));
    assertEquals(IResult.ok(new FindInput(Format.TSV, List.of("a"),
        new Period.Seasons(List.of(new Season(2026, Season.Name.AUTUMN))), Set.of(Status.HIT), true)),
        map("/find #word a #season 2026-autumn #hit #plain"));

    assertEquals(IResult.err("season と from・to は同時に指定できません"),
        map("/find #season next #from 2027-01"));
    assertEquals(IResult.err("season は 2027-winter（winter・spring・summer・autumn）・now・next で指定してください: 2027-fall"),
        map("/find #season 2027-fall"));
  }

  @Test
  void fromParseFailureUsesCommandSpecificMessage() {
    assertEquals(IResult.err(
        "from は年月・日付・日時で指定してください（例: 2026-10、2026-10-01、2026-10-01T00:00）"),
        map("/find #word anime #from 2026-02-30"));
    assertEquals(IResult.err("パラメータが重複しています: from"),
        map("/find #word anime #from 2026-10 #from 2026-11"));
  }

  @Test
  void rejectsInvalidOrDuplicateFrom() {
    for(String suffix : List.of("invalid", "2026-02-30T00:00", "2026-02-29", "2026-04-31",
        "2026-00", "2026-13", "2026", "2026-1", "2026-10T12:00", "",
        "2026-10-01T00:00, 2026-10-02T00:00",
        "2026-10-01T00:00 #from 2026-10-02T00:00")) {
      assertInstanceOf(IResult.Err.class, map("/find #word anime #from " + suffix), suffix);
    }
  }

  @Test
  void unknownCommandAndMissingWordAndPeriodReturnMessages() {
    assertEquals(IResult.err("不明なコマンドです: unknown"), map("/unknown"));
    assertEquals(IResult.err("word か期間（season・from・to）を指定してください"), map("/find"));
  }

  private static Period range(OffsetDateTime from) {
    return new Period.Range(from, null);
  }
  @Test
  void readerErrorsPropagateAsFailures() {
    for(String input : List.of(
        "/find #format unknown #word anime",
        "/find #format tsv #format md #word anime",
        "/find #format #word anime",
        "/find #word")) {
      var parsed = new MugCommandSyntaxParser().parse(input)
          .fold(value -> value, error -> fail(error));
      var reader = new CommandElementReader(parsed);
      IResult<?, String> expected = input.equals("/find #word")
          ? reader.readStrList("word")
          : reader.readEnum("format", Format.class);
      assertInstanceOf(IResult.Err.class, expected);
      assertEquals(expected, registry.map(parsed), input);
    }
  }

  // ###########################################################################
  // MARK: /reserve

  private static final String FIND_HEADER = "状態\t検索語\t放送開始日\t時刻\t曜日\t放送局\tタイトル\t公式URL\t理由";

  @Test
  void reserveTakesTitleColumnFromPastedFindTsv() {
    // タイトルのカンマ・#、空欄（末尾のタブ）も行の値として崩れずに届く。未登録の行はタイトルが検索語。
    assertEquals(IResult.ok(new ReserveInput(List.of(
        Entry.ok("SHIROBAKO"), Entry.ok("ぼく, わたし #2"), Entry.ok("放送未定アニメ"), Entry.ok("わたし")))),
        map(String.join("\n", "/reserve", FIND_HEADER,
            "放送予定\tSHIROBAKO\t2026-10-02\t23:00\t金\tTOKYO MX\tSHIROBAKO\thttps://example.com\t",
            "放送予定\tぼく\t2026-10-03\t01:30\t土\tBS11\tぼく, わたし #2\t\t",
            "",
            "対象外\t放送\t\t\t\t\t放送未定アニメ\t\t日時未定",
            "未登録\tわたし\t\t\t\t\tわたし\t\t")));
  }

  @Test
  void reserveTakesTitleColumnFromPlainFindTsvWithEmptyLeadingColumns() {
    assertEquals(IResult.ok(new ReserveInput(List.of(
        Entry.ok("SHIROBAKO"), Entry.ok("放送未定アニメ"), Entry.ok("わたし")))),
        map(String.join("\n", "/reserve", "放送開始日\t時刻\t曜日\t放送局\tタイトル\t公式URL",
            "2026-10-02\t23:00\t金\tTOKYO MX\tSHIROBAKO\thttps://example.com",
            "\t\t\t\t放送未定アニメ\thttps://example.com",
            "\t\t\t\tわたし\t")));
  }

  @Test
  void reserveFindsTitleColumnByHeaderName() {
    assertEquals(IResult.ok(new ReserveInput(List.of(Entry.ok("SHIROBAKO")))),
        map("/reserve\nタイトル\t放送局\nSHIROBAKO\tTOKYO MX"));
  }

  @Test
  void reserveTreatsEachLineAsTitleWithoutHeader() {
    assertEquals(IResult.ok(new ReserveInput(List.of(
        Entry.ok("SHIROBAKO"), Entry.ok("ぼく, わたし"), Entry.ok("#コンパス 戦闘摂理解析システム")))),
        map("/reserve\nSHIROBAKO\nぼく, わたし\n\"#コンパス 戦闘摂理解析システム\""));
    // 行頭のタブはパーサーが残すが、見出しのない行では前後の空白として除く。
    assertEquals(IResult.ok(new ReserveInput(List.of(Entry.ok("SHIROBAKO")))), map("/reserve\n\tSHIROBAKO"));
  }

  @Test
  void reserveKeepsMalformedRowsAsErrors() {
    assertEquals(IResult.ok(new ReserveInput(List.of(
        Entry.ok("SHIROBAKO"), Entry.err("2026-10-03 01:30 土", "タイトルがありません")))),
        map(String.join("\n", "/reserve", "放送開始日\t時刻\t曜日\t放送局\tタイトル\t公式URL",
            "2026-10-02\t23:00\t金\tTOKYO MX\tSHIROBAKO\t",
            "2026-10-03\t01:30\t土")));
    assertEquals(IResult.ok(new ReserveInput(List.of(
        Entry.err("SHIROBAKO TOKYO MX", "TSVの先頭行に「タイトル」列の見出しが必要です")))),
        map("/reserve\nSHIROBAKO\tTOKYO MX"));
  }

  @Test
  void reserveRejectsEmptyOrInlineArguments() {
    assertEquals(IResult.err("予約する作品を2行目以降に入力してください"), map("/reserve"));
    assertEquals(IResult.err("予約する作品を2行目以降に入力してください"), map("/reserve\n" + FIND_HEADER));

    for(String input : List.of("/reserve SHIROBAKO", "/reserve #word SHIROBAKO", "/reserve\n#コンパス 戦闘")) {
      assertEquals(IResult.err("予約する作品は2行目以降に1行ずつ入力してください"), map(input), input);
    }
  }

  private IResult<IInput, String> map(String input) {
    var parsed = new MugCommandSyntaxParser().parse(input)
        .fold(value -> value, error -> fail(error));
    return registry.map(parsed);
  }
}
