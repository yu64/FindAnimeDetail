package app.presentation.mapper;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

import app.infrastructure.MugCommandParser;
import app.usecase.command.ICommand;
import app.usecase.command.ICommand.FindCommand;
import app.usecase.command.ICommand.FindCommand.Format;
import app.usecase.command.ICommand.HelpCommand;
import app.util.IResult;

class CommandMapperTest {

  private final CommandMapper mapper = new CommandMapper();

  @Test
  void mapsHelpAndFindWithDefaultOrExplicitFormat() {
    assertEquals(IResult.ok(new HelpCommand("")), map("/help"));
    assertEquals(IResult.ok(new FindCommand(Format.TSV, List.of("anime"))),
      map("/find #word anime"));
    assertEquals(IResult.ok(new FindCommand(Format.MARKDOWN, List.of("a", "b"))),
      map("/find #format markdown #word a, b"));
    assertEquals(IResult.ok(new FindCommand(Format.TSV, List.of("a", "b"))),
      map("/find tsv a, b"));
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
      "/find #format tsv #format markdown #word anime",
      "/find #format #word anime",
      "/find #word"
    )) {
      var parsed = new MugCommandParser().parse(input)
        .fold(value -> value, error -> fail(error));
      var reader = new CommandElementReader(parsed);
      IResult<?, String> expected = input.equals("/find #word")
        ? reader.readStrList("word")
        : reader.readEnum("format", Format.class);
      assertInstanceOf(IResult.Err.class, expected);
      assertEquals(expected, mapper.map(parsed), input);
    }
  }

  private IResult<ICommand, String> map(String input) {
    var parsed = new MugCommandParser().parse(input)
      .fold(value -> value, error -> fail(error));
    return mapper.map(parsed);
  }
}
