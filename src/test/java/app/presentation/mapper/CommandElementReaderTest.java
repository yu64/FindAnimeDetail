package app.presentation.mapper;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import app.util.IResult;
import org.junit.jupiter.api.Test;

import app.presentation.mapper.ParsedCommand.ICommandElement;
import app.presentation.mapper.ParsedCommand.ICommandElement.*;

class CommandElementReaderTest {

  @Test
  void namedValuesDoNotConsumeUnnamedValues()
  {
    var reader = reader(new ValueElement("first"), new ParamElement("title", "named"),
      new QuasiUnnamedParamElement("second"));

    assertEquals(IResult.ok(Optional.of("named")), reader.readStr("title"));
    assertEquals(IResult.ok(Optional.of("named")), reader.readStr("title"));
    assertEquals(IResult.ok(Optional.of("first")), reader.readStr("a"));
    assertEquals(IResult.ok(Optional.of("second")), reader.readStr("b"));
    assertEquals(IResult.ok(Optional.empty()), reader.readStr("c"));
  }

  @Test
  void mismatchedUnnamedValueIsLeftForNextRead()
  {
    var reader = reader(new UnnamedFlatListElement(List.of("a", "b")), new ValueElement("c"));

    assertEquals(IResult.ok(Optional.empty()), reader.readStr("title"));
    assertEquals(IResult.ok(Optional.of(List.of("a", "b"))), reader.readStrList("tags"));
    assertEquals(IResult.ok(Optional.of("c")), reader.readStr("next"));
    assertEquals(IResult.ok(Optional.empty()), reader.readStrList("missing"));
  }

  @Test
  void namedListsMergeInEncounterOrder()
  {
    var reader = reader(new ParamElement("tags", "a"), new ValueElement("unnamed"),
      new FlatListElement("tags", List.of("b", "c")), new ParamElement("other", "ignored"),
      new ListElement("tags", List.of("d", "e")), new ParamElement("tags", "f"));

    assertEquals(IResult.ok(Optional.of(List.of("a", "b", "c", "d", "e", "f"))), reader.readStrList("tags"));
    assertEquals(IResult.ok(Optional.of("unnamed")), reader.readStr("next"));
  }

  @Test
  void allUnnamedFormsAreReadOneElementAtATime()
  {
    List<ICommandElement> elements = List.of(new ValueElement("a"), new QuasiUnnamedParamElement("a"),
      new UnnamedFlatListElement(List.of("a")), new QuasiUnnamedFlatListElement(List.of("a")),
      new UnnamedListElement(List.of("a")), new QuasiUnnamedListElement(List.of("a")));
    var reader = new CommandElementReader(new ParsedCommand("find", elements));

    for(var _ : elements) {
      assertEquals(IResult.ok(Optional.of(List.of("a"))), reader.readStrList("tags"));
    }
    assertEquals(IResult.ok(Optional.empty()), reader.readStrList("tags"));
  }

  @Test
  void singleValuesAndSwitchesRejectDuplicatesRegardlessOfType()
  {
    var strings = reader(new ParamElement("x", "a"), new ParamElement("x", "b"));
    var mixed = reader(new ParamElement("x", "a"), new SwitchElement("x"));
    var switches = reader(new SwitchElement("x"), new SwitchElement("x"));

    assertFailure(strings.readStr("x"));
    assertFailure(mixed.readStr("x"));
    assertFailure(mixed.readSwitch("x"));
    assertFailure(switches.readSwitch("x"));
    assertFailure(mixed.readStrList("x"));
  }

  @Test
  void namedTypeErrorsDoNotFallBackOrConsumeUnnamedValues()
  {
    var reader = reader(new FlatListElement("list", List.of("a")), new SwitchElement("flag"),
      new ParamElement("text", "b"), new ValueElement("remaining"));

    assertFailure(reader.readStr("list"));
    assertFailure(reader.readStr("flag"));
    assertFailure(reader.readStrList("flag"));
    assertFailure(reader.readSwitch("text"));
    assertEquals(IResult.ok(Optional.of("remaining")), reader.readStr("next"));
  }

  @Test
  void switchesNeverConsumeUnnamedValues()
  {
    var reader = reader(new ValueElement("a"), new SwitchElement("debug"));

    assertEquals(IResult.ok(Optional.empty()), reader.readSwitch("missing"));
    assertEquals(IResult.ok(Optional.of(true)), reader.readSwitch("debug"));
    assertEquals(IResult.ok(Optional.of("a")), reader.readStr("next"));
  }

  @Test
  void emptyListIsPresentAndConsumesOneUnnamedElement()
  {
    var reader = reader(new UnnamedListElement(List.of()), new ValueElement("next"));

    assertEquals(IResult.ok(Optional.of(List.of())), reader.readStrList("tags"));
    assertEquals(IResult.ok(Optional.of("next")), reader.readStr("next"));
  }

  @Test
  void conversionsUseNamedValuesAndUnnamedOrder()
  {
    var reader = reader(new ValueElement("-12"), new ParamElement("format", "tsv"),
      new QuasiUnnamedParamElement("MarkDown"));

    assertEquals(IResult.ok(Optional.of(TestFormat.TSV)), reader.readEnum("format", TestFormat.class));
    assertEquals(IResult.ok(Optional.of(-12)), reader.readInt("count"));
    assertEquals(IResult.ok(Optional.of(TestFormat.MARKDOWN)), reader.readEnum("next", TestFormat.class));
    assertEquals(IResult.ok(Optional.empty()), reader.readInt("missing"));
    assertEquals(IResult.ok(Optional.empty()), reader.readEnum("missing", TestFormat.class));
  }

  @Test
  void invalidConversionsFailAndConsumeReadUnnamedStrings()
  {
    var reader = reader(new ValueElement("unknown"), new ValueElement("abc"),
      new ValueElement("2147483648"), new ValueElement("42"));

    assertFailure(reader.readEnum("format", TestFormat.class));
    assertFailure(reader.readInt("count"));
    assertFailure(reader.readInt("count"));
    assertEquals(IResult.ok(Optional.of(42)), reader.readInt("count"));
  }

  @Test
  void conversionsPreserveStringReadingValidation()
  {
    var reader = reader(new ParamElement("x", "1"), new SwitchElement("x"),
      new UnnamedListElement(List.of("TSV")));

    assertFailure(reader.readInt("x"));
    assertFailure(reader.readEnum("x", TestFormat.class));
    assertEquals(IResult.ok(Optional.empty()), reader.readInt("missing"));
    assertEquals(IResult.ok(Optional.empty()), reader.readEnum("missing", TestFormat.class));
    assertEquals(IResult.ok(Optional.of(List.of("TSV"))), reader.readStrList("remaining"));
  }

  private void assertFailure(IResult<?, String> result)
  {
    switch(result) {
      case IResult.Ok(var value) -> fail("Expected failure: " + value);
      case IResult.Err(var error) -> assertFalse(error.isBlank());
    }
  }

  private enum TestFormat {
    TSV,
    MARKDOWN
  }

  private CommandElementReader reader(ICommandElement... elements)
  {
    return new CommandElementReader(new ParsedCommand("find", List.of(elements)));
  }
}
