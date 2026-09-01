package app.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import app.usecase.dto.ParsedCommand;
import java.util.List;
import org.junit.jupiter.api.Test;

class MugCommandParserTest {

    private final MugCommandParser parser = new MugCommandParser();

    @Test
    void parseCommandWithNamedParamAndSwitch() {
        ParsedCommand parsed = parser.parse("/anime #title shingeki #debug");
        System.out.println(parsed);

        assertEquals("anime", parsed.command());
        assertEquals(2, parsed.elements().size());

        ParsedCommand.ICommandElement.ParamElement title =
            assertInstanceOf(
                ParsedCommand.ICommandElement.ParamElement.class,
                parsed.elements().get(0)
            );
        assertEquals("title", title.param());
        assertEquals("shingeki", title.value());

        ParsedCommand.ICommandElement.SwitchElement debug =
            assertInstanceOf(
                ParsedCommand.ICommandElement.SwitchElement.class,
                parsed.elements().get(1)
            );
        assertEquals("debug", debug.param());
    }

    @Test
    void parseCommandWithFlatListAndUnnamedValues() {
        ParsedCommand parsed = parser.parse("/anime #tags anime, drama, action one two");
        System.out.println(parsed);

        assertEquals("anime", parsed.command());
        assertEquals(3, parsed.elements().size());

        ParsedCommand.ICommandElement.FlatListElement tags =
            assertInstanceOf(
                ParsedCommand.ICommandElement.FlatListElement.class,
                parsed.elements().get(0)
            );
        assertEquals("tags", tags.param());
        assertEquals(List.of("anime", "drama", "action"), tags.values());

        ParsedCommand.ICommandElement.ValueElement first =
            assertInstanceOf(
                ParsedCommand.ICommandElement.ValueElement.class,
                parsed.elements().get(1)
            );
        assertEquals("one", first.value());

        ParsedCommand.ICommandElement.ValueElement second =
            assertInstanceOf(
                ParsedCommand.ICommandElement.ValueElement.class,
                parsed.elements().get(2)
            );
        assertEquals("two", second.value());
    }

    @Test
    void parseQuasiUnnamedList() {
        ParsedCommand parsed = parser.parse("/anime #\nalpha\nbeta\ngamma");
        System.out.println(parsed);

        assertEquals("anime", parsed.command());
        assertEquals(1, parsed.elements().size());

        ParsedCommand.ICommandElement.QuasiUnnamedListElement values =
            assertInstanceOf(
                ParsedCommand.ICommandElement.QuasiUnnamedListElement.class,
                parsed.elements().get(0)
            );
        assertEquals(List.of("alpha", "beta", "gamma"), values.values());
    }

    @Test
    void parseNamedListAndSwitchInSequence() {
        ParsedCommand parsed = parser.parse("/anime #items\nfirst\nsecond #debug");
        System.out.println(parsed);

        assertEquals("anime", parsed.command());
        assertEquals(2, parsed.elements().size());

        ParsedCommand.ICommandElement.ListElement items =
            assertInstanceOf(
                ParsedCommand.ICommandElement.ListElement.class,
                parsed.elements().get(0)
            );
        assertEquals("items", items.param());
        assertEquals(List.of("first", "second"), items.values());

        ParsedCommand.ICommandElement.SwitchElement debug =
            assertInstanceOf(
                ParsedCommand.ICommandElement.SwitchElement.class,
                parsed.elements().get(1)
            );
        assertEquals("debug", debug.param());
    }

    @Test
    void parseUnnamedFlatList() {
        ParsedCommand parsed = parser.parse("/anime one, two, three");
        System.out.println(parsed);

        assertEquals("anime", parsed.command());
        assertEquals(1, parsed.elements().size());

        ParsedCommand.ICommandElement.UnnamedFlatListElement values =
            assertInstanceOf(
                ParsedCommand.ICommandElement.UnnamedFlatListElement.class,
                parsed.elements().get(0)
            );
        assertEquals(List.of("one", "two", "three"), values.values());
    }

    @Test
    void parseMixedElementsInSequence() {
        ParsedCommand parsed = parser.parse("/anime #title shingeki #debug #tags action, drama, comedy alpha beta");
        System.out.println(parsed);

        assertEquals("anime", parsed.command());
        assertEquals(5, parsed.elements().size());

        ParsedCommand.ICommandElement.ParamElement title =
            assertInstanceOf(
                ParsedCommand.ICommandElement.ParamElement.class,
                parsed.elements().get(0)
            );
        assertEquals("title", title.param());
        assertEquals("shingeki", title.value());

        ParsedCommand.ICommandElement.SwitchElement debug =
            assertInstanceOf(
                ParsedCommand.ICommandElement.SwitchElement.class,
                parsed.elements().get(1)
            );
        assertEquals("debug", debug.param());

        ParsedCommand.ICommandElement.FlatListElement tags =
            assertInstanceOf(
                ParsedCommand.ICommandElement.FlatListElement.class,
                parsed.elements().get(2)
            );
        assertEquals("tags", tags.param());
        assertEquals(List.of("action", "drama", "comedy"), tags.values());

        ParsedCommand.ICommandElement.ValueElement alpha =
            assertInstanceOf(
                ParsedCommand.ICommandElement.ValueElement.class,
                parsed.elements().get(3)
            );
        assertEquals("alpha", alpha.value());

        ParsedCommand.ICommandElement.ValueElement beta =
            assertInstanceOf(
                ParsedCommand.ICommandElement.ValueElement.class,
                parsed.elements().get(4)
            );
        assertEquals("beta", beta.value());
    }

    @Test
    void parseNamedListFollowedByUnnamedValues() {
        ParsedCommand parsed = parser.parse("/anime #items\nfirst\nsecond gamma delta");
        System.out.println(parsed);

        assertEquals("anime", parsed.command());
        assertEquals(3, parsed.elements().size());

        ParsedCommand.ICommandElement.ListElement items =
            assertInstanceOf(
                ParsedCommand.ICommandElement.ListElement.class,
                parsed.elements().get(0)
            );
        assertEquals("items", items.param());
        assertEquals(List.of("first", "second"), items.values());

        ParsedCommand.ICommandElement.ValueElement gamma =
            assertInstanceOf(
                ParsedCommand.ICommandElement.ValueElement.class,
                parsed.elements().get(1)
            );
        assertEquals("gamma", gamma.value());

        ParsedCommand.ICommandElement.ValueElement delta =
            assertInstanceOf(
                ParsedCommand.ICommandElement.ValueElement.class,
                parsed.elements().get(2)
            );
        assertEquals("delta", delta.value());
    }

    @Test
    void parseQuasiUnnamedParamAndFlatList() {
        ParsedCommand parsed = parser.parse("/anime # alpha, beta, gamma #title final");
        System.out.println(parsed);

        assertEquals("anime", parsed.command());
        assertEquals(2, parsed.elements().size());

        ParsedCommand.ICommandElement.QuasiUnnamedFlatListElement quasi =
            assertInstanceOf(
                ParsedCommand.ICommandElement.QuasiUnnamedFlatListElement.class,
                parsed.elements().get(0)
            );
        assertEquals(List.of("alpha", "beta", "gamma"), quasi.values());

        ParsedCommand.ICommandElement.ParamElement title =
            assertInstanceOf(
                ParsedCommand.ICommandElement.ParamElement.class,
                parsed.elements().get(1)
            );
        assertEquals("title", title.param());
        assertEquals("final", title.value());
    }
}
