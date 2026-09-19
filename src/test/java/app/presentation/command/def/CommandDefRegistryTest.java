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
import app.usecase.IInput.FindInput.Format;
import app.util.IResult;

class CommandDefRegistryTest {

  private final CommandDefRegistry registry = new CommandDefRegistry(
    List.of(new HelpCommandDef(), new FindCommandDef())
  );

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
  void mapsCompleteSwitchWithOtherOptionsAndRejectsValuesOrDuplicates() {
    assertEquals(IResult.ok(new FindInput(Format.TSV, List.of("anime"), null, true)),
      map("/find #word anime #complete"));
    var from = OffsetDateTime.parse("2026-10-01T00:00:00+09:00");
    assertEquals(IResult.ok(new FindInput(Format.MD, List.of("a", "b"), from, true)),
      map("/find #complete #format md #word a, b #from 2026-10-01T00:00"));
    for (String suffix : List.of("#complete true", "#complete false", "#complete #complete")) {
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
    for (String value : List.of("2026-10", "2026-10-01")) {
      assertEquals(IResult.ok(new FindInput(Format.TSV, List.of("anime"),
          OffsetDateTime.parse("2026-10-01T00:00:00+09:00"))),
        map("/find #word anime #from " + value));
    }
    assertEquals(IResult.ok(new FindInput(Format.MD, List.of("a", "b"),
        OffsetDateTime.parse("2028-02-29T00:00:00+09:00"), true)),
      map("/find #format md #word a, b #from 2028-02-29 #complete"));
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
    for (String suffix : List.of("invalid", "2026-02-30T00:00", "2026-02-29", "2026-04-31",
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
      "/find #word"
    )) {
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

  private IResult<IInput, String> map(String input) {
    var parsed = new MugCommandSyntaxParser().parse(input)
      .fold(value -> value, error -> fail(error));
    return registry.map(parsed);
  }
}
