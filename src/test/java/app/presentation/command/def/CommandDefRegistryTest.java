package app.presentation.command.def;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

import app.infrastructure.MugCommandSyntaxParser;
import app.presentation.command.parse.CommandElementReader;
import app.usecase.IInput;
import app.usecase.IInput.FindInput;
import app.usecase.IInput.HelpInput;
import app.usecase.IInput.ReserveInput;
import app.usecase.IInput.ReserveInput.Entry;
import app.usecase.IInput.FindInput.Format;
import app.util.IResult;

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
  void mapsAllSwitchWithOtherOptionsAndRejectsValuesOrDuplicates() {
    assertEquals(IResult.ok(new FindInput(Format.TSV, List.of("anime"), null, true)),
        map("/find #word anime #all"));
    var from = OffsetDateTime.parse("2026-10-01T00:00:00+09:00");
    assertEquals(IResult.ok(new FindInput(Format.MD, List.of("a", "b"), from, true)),
        map("/find #all #format md #word a, b #from 2026-10-01T00:00"));
    for(String suffix : List.of("#all true", "#all false", "#all #all")) {
      assertInstanceOf(IResult.Err.class, map("/find #word anime " + suffix));
    }
  }

  @Test
  void mapsFromWithJapaneseTimeOrExplicitOffset() {
    var from = OffsetDateTime.parse("2026-10-01T00:00:00+09:00");
    assertEquals(IResult.ok(new FindInput(Format.TSV, List.of("anime"), from)),
        map("/find #word anime #from 2026-10-01T00:00"));
    assertEquals(IResult.ok(new FindInput(Format.TSV, List.of("anime"), from)),
        map("/find #from 2026-10-01T00:00:00+09:00 #word anime"));
    var utc = OffsetDateTime.parse("2026-09-30T15:00:00Z");
    assertEquals(IResult.ok(new FindInput(Format.TSV, List.of("anime"), utc)),
        map("/find #word anime #from 2026-09-30T15:00:00Z"));
    assertEquals(IResult.ok(new FindInput(Format.TSV, List.of("a", "b"), from)),
        map("/find #from 2026-10-01T00:00\na\nb"));
  }

  @Test
  void mapsFromWithoutTimeOrDayToJapaneseMidnight() {
    for(String value : List.of("2026-10", "2026-10-01")) {
      assertEquals(IResult.ok(new FindInput(Format.TSV, List.of("anime"),
          OffsetDateTime.parse("2026-10-01T00:00:00+09:00"))),
          map("/find #word anime #from " + value));
    }
    assertEquals(IResult.ok(new FindInput(Format.MD, List.of("a", "b"),
        OffsetDateTime.parse("2028-02-29T00:00:00+09:00"), true)),
        map("/find #format md #word a, b #from 2028-02-29 #all"));
    assertEquals(IResult.ok(new FindInput(Format.TSV, List.of("a", "b"),
        OffsetDateTime.parse("2026-10-01T00:00:00+09:00"))),
        map("/find #from 2026-10\na\nb"));
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
  void unknownCommandAndMissingWordReturnMessages() {
    assertEquals(IResult.err("不明なコマンドです: unknown"), map("/unknown"));
    assertEquals(IResult.err("word は必須です"), map("/find"));
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

  private static final String FIND_HEADER = "放送開始日\t時刻\t曜日\t放送局\tタイトル\t公式URL";

  @Test
  void reserveTakesTitleColumnFromPastedFindTsv() {
    // タイトルのカンマ・#、公式URLの空欄（末尾のタブ）も行の値として崩れずに届く。
    assertEquals(IResult.ok(new ReserveInput(List.of(
        Entry.ok("SHIROBAKO"), Entry.ok("ぼく, わたし #2"), Entry.ok("放送未定アニメ")))),
        map(String.join("\n", "/reserve", FIND_HEADER,
            "2026-10-02\t23:00\t金\tTOKYO MX\tSHIROBAKO\thttps://example.com",
            "2026-10-03\t01:30\t土\tBS11\tぼく, わたし #2\t",
            "",
            "N/A\tN/A\tN/A\tN/A\t放送未定アニメ\t")));
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
  }

  @Test
  void reserveKeepsMalformedRowsAsErrors() {
    assertEquals(IResult.ok(new ReserveInput(List.of(
        Entry.ok("SHIROBAKO"), Entry.err("2026-10-03 01:30 土", "タイトルがありません")))),
        map(String.join("\n", "/reserve", FIND_HEADER,
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
