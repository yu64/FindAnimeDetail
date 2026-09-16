package app.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import app.presentation.command.parse.CommandExpression;
import app.presentation.command.parse.CommandExpression.IElement;
import app.presentation.command.parse.CommandExpression.IElement.*;
import app.util.IResult;

/** docs/parser.md の仕様例を期待値とする。実装の挙動に合わせて期待値を変更しない。 */
class MugCommandParserTest {

    // ###########################################################################
    // MARK: テスト共通処理

    private final MugCommandSyntaxParser parser = new MugCommandSyntaxParser();

    private void assertParsed(String input, CommandExpression expected) {
        CommandExpression actual = parser.parse(input).fold(
            command -> command,
            error -> fail("Expected successful parse of:\n" + input + "\nError: " + error)
        );
        // 要素の型・名称・値・順序・個数をまとめて検証する。
        assertEquals(expected, actual, () -> "Input:\n" + input);
    }

    private static Arguments example(String name, String input, IElement... elements) {
        return Arguments.of(name, input, new CommandExpression("hoge", List.of(elements)));
    }

    // ###########################################################################
    // MARK: 仕様書の成功例

    @DisplayName("仕様書の成功例")
    @ParameterizedTest(name = "{0}")
    @MethodSource("specificationExamples")
    void parsesSpecificationExample(String name, String input, CommandExpression expected) {
        assertParsed(input, expected);
    }

    static Stream<Arguments> specificationExamples() {
        return Stream.of(
            example("空白区切りの無名パラメータ", "/hoge 53 23 13",
                new ValueElement("53"), new ValueElement("23"), new ValueElement("13")),
            example("名前付きパラメータを入力順に保持", "/hoge #a 10 #c 20 #b 30",
                new ParamElement("a", "10"), new ParamElement("c", "20"), new ParamElement("b", "30")),
            example("コマンド直後の改行から無名リスト", """
                /hoge
                a
                b
                c
                """, new UnnamedListElement(List.of("a", "b", "c"))),
            example("無名リストは行途中の引数を解釈しない", """
                /hoge
                fuga #a 12
                """, new UnnamedListElement(List.of("fuga #a 12"))),
            example("無名リストは行全体を値として重複も保持", """
                /hoge
                fuga #a 12
                fuga #a 12
                """, new UnnamedListElement(List.of("fuga #a 12", "fuga #a 12"))),
            example("リスト内の引用文字列とカンマ", """
                /hoge
                "a, b"
                #a, b#
                a, b
                """, new UnnamedListElement(List.of("a, b", "a, b", "a, b"))),
            example("引数行と無名リストを交互に読む", """
                /hoge #a 12 12
                #b 12 12
                #c 12 12 #d 12
                hoge
                fuga #a 12
                #e 12
                xxx
                ###yyy#
                #f 12
                """,
                new ParamElement("a", "12"), new ValueElement("12"),
                new ParamElement("b", "12"), new ValueElement("12"),
                new ParamElement("c", "12"), new ValueElement("12"),
                new ParamElement("d", "12"), new UnnamedListElement(List.of("hoge", "fuga #a 12")),
                new ParamElement("e", "12"), new UnnamedListElement(List.of("xxx", "#yyy")),
                new ParamElement("f", "12")),
            example("行頭のパラメータで無名リストを終了", """
                /hoge
                fuga
                #a 12
                """, new UnnamedListElement(List.of("fuga")), new ParamElement("a", "12")),
            example("カンマ前後の空白とフラットリストの終端", "/hoge #a 12, 13 ,14 , 15 16",
                new FlatListElement("a", List.of("12", "13", "14", "15")), new ValueElement("16")),
            example("フラットリストの次行は無名リスト", """
                /hoge\s
                #a 12, 13 ,14 , 15
                16
                """, new FlatListElement("a", List.of("12", "13", "14", "15")),
                new UnnamedListElement(List.of("16"))),
            example("全引数構文の混在と空行によるリスト分離", """
                /hoge #a 12 # 12 12 #b 1,2 # 1,2 1,2 #x
                #c 0 #d 12 # 12 12 #f 1,2 # 1,2 1,2 #y
                #list
                a
                b
                #\s
                a
                b

                a
                b
                """,
                new ParamElement("a", "12"), new QuasiUnnamedParamElement("12"), new ValueElement("12"),
                new FlatListElement("b", List.of("1", "2")),
                new QuasiUnnamedFlatListElement(List.of("1", "2")),
                new UnnamedFlatListElement(List.of("1", "2")), new SwitchElement("x"),
                new ParamElement("c", "0"), new ParamElement("d", "12"),
                new QuasiUnnamedParamElement("12"), new ValueElement("12"),
                new FlatListElement("f", List.of("1", "2")),
                new QuasiUnnamedFlatListElement(List.of("1", "2")),
                new UnnamedFlatListElement(List.of("1", "2")), new SwitchElement("y"),
                new ListElement("list", List.of("a", "b")),
                new QuasiUnnamedListElement(List.of("a", "b")), new UnnamedListElement(List.of("a", "b"))),
            example("行途中の名前のみの引数はスイッチ", """
                /hoge #a #b 12 #c
                hoge
                fuga
                """, new SwitchElement("a"), new ParamElement("b", "12"), new SwitchElement("c"),
                new UnnamedListElement(List.of("hoge", "fuga"))),
            example("リストの行頭の空白を除去", """
                /hoge
                    hoge
                fuga
                """, new UnnamedListElement(List.of("hoge", "fuga"))),
            example("ハッシュ引用は改行をまたがない", """
                /hoge
                #hoge
                fuga#
                """, new ListElement("hoge", List.of("fuga#"))),
            example("パラメータの後にハッシュ引用文字列", "/hoge #a 12 #b bb#",
                new ParamElement("a", "12"), new ValueElement("b bb")),
            example("ハッシュ引用文字列の後にパラメータ", "/hoge #a 12# #b bb",
                new ValueElement("a 12"), new ParamElement("b", "bb")),
            example("値の行が続かない名前のみの行はスイッチ", """
                /hoge #a
                #b
                #c
                """, new SwitchElement("a"), new SwitchElement("b"), new SwitchElement("c")),
            example("日本語の値", "/hoge #name 山田", new ParamElement("name", "山田")),
            example("二重引用符のエスケープ", "/hoge #name \"/hoge #name \"\"inner\"\"\"",
                new ParamElement("name", "/hoge #name \"inner\""))
        );
    }

    // ###########################################################################
    // MARK: 構文ごとの基本ケース

    @DisplayName("構文ごとの基本ケース")
    @ParameterizedTest(name = "{0}")
    @MethodSource("syntaxExamples")
    void parsesIndividualSyntax(String name, String input, CommandExpression expected) {
        assertParsed(input, expected);
    }

    static Stream<Arguments> syntaxExamples() {
        return Stream.of(
            example("準無名の単一値をリストにしない", "/hoge # value", new QuasiUnnamedParamElement("value")),
            example("名前付きフラットリスト", "/hoge #name a, b", new FlatListElement("name", List.of("a", "b"))),
            example("準無名フラットリスト", "/hoge # a, b", new QuasiUnnamedFlatListElement(List.of("a", "b"))),
            example("無名フラットリスト", "/hoge a, b", new UnnamedFlatListElement(List.of("a", "b"))),
            example("行頭から名前付きリスト", """
                /hoge
                #name
                a
                b
                """, new ListElement("name", List.of("a", "b"))),
            example("行頭から準無名リスト", """
                /hoge
                #
                a
                b
                """, new QuasiUnnamedListElement(List.of("a", "b"))),
            example("二重引用符でカンマと空白を保護", "/hoge \"hoge, fuga\"", new ValueElement("hoge, fuga")),
            example("ハッシュでカンマと空白を保護", "/hoge #hoge, fuga#", new ValueElement("hoge, fuga")),
            example("二重引用符を重ねて文字として扱う", "/hoge \"hoge, \"\"fuga\" next",
                new ValueElement("hoge, \"fuga"), new ValueElement("next")),
            example("ハッシュを重ねて文字として扱う", "/hoge #hoge, ##fuga# next",
                new ValueElement("hoge, #fuga"), new ValueElement("next"))
        );
    }

    // ###########################################################################
    // MARK: コマンド接頭辞

    @DisplayName("仕様で定義されたコマンド接頭辞")
    @ParameterizedTest
    @ValueSource(strings = {"/", "$", "@"})
    void acceptsCommandPrefix(String prefix) {
        assertParsed(prefix + "hoge", new CommandExpression("hoge", List.of()));
    }

    // ###########################################################################
    // MARK: 改行コード・末尾改行

    @DisplayName("改行コードと末尾改行によってリストの結果が変わらない")
    @ParameterizedTest(name = "{0}, trailing newline = {2}")
    @MethodSource("lineEndings")
    void parsesMultilineInput(String name, String newline, boolean trailingNewline) {
        String input = String.join(newline, "/hoge", "a", "b") + (trailingNewline ? newline : "");
        assertParsed(input, new CommandExpression("hoge", List.of(new UnnamedListElement(List.of("a", "b")))));
    }

    static Stream<Arguments> lineEndings() {
        return Stream.of(
            Arguments.of("LF", "\n", false), Arguments.of("LF", "\n", true),
            Arguments.of("CRLF", "\r\n", false), Arguments.of("CRLF", "\r\n", true),
            Arguments.of("CR", "\r", false), Arguments.of("CR", "\r", true)
        );
    }

    // ###########################################################################
    // MARK: 不正な入力

    @DisplayName("不正な入力は例外ではなく失敗結果を返す")
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidExamples")
    void rejectsInvalidInput(String name, String input) {
        switch (parser.parse(input)) {
            case IResult.Ok(var command) -> fail("Expected failure for:\n" + input + "\nParsed: " + command);
            case IResult.Err(var error) -> assertFalse(error.isBlank(), "Error should explain the failure");
        }
    }

    static Stream<Arguments> invalidExamples() {
        return Stream.of(
            // docs/parser.md のパースエラー例。
            Arguments.of("閉じた引用の直後に区切りがない", "/hoge #a 12#bb"),
            Arguments.of("値のない準無名引数", "/hoge #"),
            Arguments.of("値のない準無名引数が続く", """
                /hoge #
                #
                """),
            Arguments.of("空入力", ""),
            Arguments.of("コマンド接頭辞がない", "hoge"),
            Arguments.of("コマンド名がない", "/"),
            Arguments.of("仕様外のパーセント接頭辞", "%hoge"),
            Arguments.of("仕様外のバックスラッシュ接頭辞", "\\hoge")
        );
    }
}
