package app.infrastructure;

import app.presentation.ICommandParser;
import app.usecase.dto.ParsedCommand;
import app.usecase.dto.ParsedCommand.ICommandElement;
import jakarta.enterprise.context.ApplicationScoped;

import com.google.common.labs.parse.Parser;
import java.util.*;
import java.util.stream.Collectors;


/**
 * Google MUG ParserCombinatorsを使用した汎用コマンドパーサー
 */
@ApplicationScoped
public class MugCommandParser implements ICommandParser {

  // ===========================================================================
  // MARK: 基本要素

  /** 改行 */
  private static final Parser<String> LF = Parser.anyOf(
    Parser.string("\r\n").source(),
    Parser.one('\n').source(),
    Parser.one('\r').source()
  );

  /** 空白0個以上（半角空白、全角空白、タブ） */
  private static final Parser<String>.OrEmpty SPACES =
    Parser.one("[ \t\u3000]").zeroOrMore().map(s -> "");

  /** 空白1個以上 */
  private static final Parser<String> SPACE_SEP = 
    Parser.one("[ \t\u3000]").atLeastOnce().map(s -> "");

  // ===========================================================================
  // MARK: コマンド

  /** コマンド接頭辞（/, %, \） */
  private static final Parser<String> COMMAND_PREFIX =
    Parser.anyOf(Parser.one('/'), Parser.one('%'), Parser.one('\\')).source();

  /** コマンド名（非記号文字） */
  private static final Parser<String> COMMAND_NAME = 
    Parser.consecutive("[a-zA-Z0-9_]");

  /** コマンド構文: (/|%|\\)command */
  private static final Parser<String> COMMAND_SYNTAX =
    Parser.sequence(
      COMMAND_PREFIX,
      COMMAND_NAME,
      (prefix, name) -> name
    );

  // ===========================================================================
  // MARK: パラメータ・値

  /** パラメータ名（非記号文字） */
  private static final Parser<String> PARAM_NAME = 
    Parser.consecutive("[a-zA-Z0-9_]");

  /** 値（非記号文字の連続、スペース区切り） */
  private static final Parser<String> VALUE = 
    Parser.consecutive("[a-zA-Z0-9_]");

  /** カンマ区切り（カンマの前後に空白を許可） */
  private static final Parser<Character> COMMA_SEPARATOR =
    Parser.one(',').between(SPACES, SPACES);

  /** カンマ区切り複数値 */
  private static final Parser<List<String>> COMMA_SEPARATED_VALUES = 
    VALUE.atLeastOnceDelimitedBy(
      COMMA_SEPARATOR,
      Collectors.toList()
    );

  /** フラットリストは 2 個以上の値でのみ成立させる */
  private static final Parser<List<String>> MULTI_VALUE_FLAT_LIST =
    Parser.sequence(
      VALUE,
      COMMA_SEPARATOR,
      COMMA_SEPARATED_VALUES,
      (first, separator, rest) -> {
        List<String> values = new ArrayList<>();
        values.add(first);
        values.addAll(rest);
        return values;
      }
    );

  // ===========================================================================
  // MARK: 個別パーサー定義
  

  /** パラメータ構文: #param value */
  private static final Parser<ICommandElement> PARAM_PARSER =
    Parser.sequence(
      Parser.one('#'),
      PARAM_NAME,
      SPACE_SEP,
      VALUE,
      (hash, param, space, value) -> 
        new ICommandElement.ParamElement(param, value)
    );

  /** スイッチ構文: #param */
  private static final Parser<ICommandElement> SWITCH_PARSER =
    Parser.sequence(
      Parser.one('#'),
      PARAM_NAME,
      (hash, param) -> 
        new ICommandElement.SwitchElement(param)
    );

  /** フラットリスト構文: #param value1, value2, ... */
  private static final Parser<ICommandElement> FLAT_LIST_PARSER =
    Parser.sequence(
      Parser.one('#'),
      PARAM_NAME,
      SPACE_SEP,
      MULTI_VALUE_FLAT_LIST,
      (hash, param, space, values) -> 
        new ICommandElement.FlatListElement(param, values)
    );

  /** リスト構文: #param\nvalue1\nvalue2\n... */
  private static final Parser<ICommandElement> LIST_PARSER =
    Parser.sequence(
      Parser.one('#'),
      PARAM_NAME,
      LF,
      VALUE.atLeastOnceDelimitedBy(LF, Collectors.toList()),
      (hash, param, lf, values) -> 
        new ICommandElement.ListElement(param, values)
    );

  /** 準無名パラメータ構文: # value */
  private static final Parser<ICommandElement> QUASI_UNNAMED_PARAM_PARSER =
    Parser.sequence(
      Parser.one('#'),
      SPACE_SEP,
      VALUE,
      (hash, space, value) -> 
        new ICommandElement.QuasiUnnamedParamElement(value)
    );

  /** 無名フラットリスト構文: value1, value2, ... */
  private static final Parser<ICommandElement> UNNAMED_FLAT_LIST_PARSER =
    MULTI_VALUE_FLAT_LIST
      .map(values -> new ICommandElement.UnnamedFlatListElement(values));

  /** 準無名フラットリスト構文: # value1, value2, ... */
  private static final Parser<ICommandElement> QUASI_UNNAMED_FLAT_LIST_PARSER =
    Parser.sequence(
      Parser.one('#'),
      SPACE_SEP,
      COMMA_SEPARATED_VALUES,
      (hash, space, values) -> 
        new ICommandElement.QuasiUnnamedFlatListElement(values)
    );

  /** 無名リスト構文: \nvalue1\nvalue2\n... */
  private static final Parser<ICommandElement> UNNAMED_LIST_PARSER =
    Parser.sequence(
      LF,
      VALUE.atLeastOnceDelimitedBy(LF, Collectors.toList()),
      (lf, values) -> 
        new ICommandElement.UnnamedListElement(values)
    );

  /** 準無名リスト構文: #\nvalue1\nvalue2\n... */
  private static final Parser<ICommandElement> QUASI_UNNAMED_LIST_PARSER =
    Parser.sequence(
      Parser.one('#'),
      LF,
      VALUE.atLeastOnceDelimitedBy(LF, Collectors.toList()),
      (hash, lf, values) -> 
        new ICommandElement.QuasiUnnamedListElement(values)
    );

  /** 無名値 */
  private static final Parser<ICommandElement> VALUE_PARSER =
    VALUE.map(v -> new ICommandElement.ValueElement(v));

  /** どれかのコマンド要素 */
  private static final Parser<ICommandElement> COMMAND_ELEMENT =
    Parser.anyOf(
      QUASI_UNNAMED_LIST_PARSER,      // #\nval1\nval2
      UNNAMED_LIST_PARSER,            // \nval1\nval2
      LIST_PARSER,                    // #param\nval1\nval2
      FLAT_LIST_PARSER,               // #param val1, val2
      QUASI_UNNAMED_FLAT_LIST_PARSER, // # val1, val2
      UNNAMED_FLAT_LIST_PARSER,       // val1, val2
      PARAM_PARSER,                   // #param value
      QUASI_UNNAMED_PARAM_PARSER,     // # value
      SWITCH_PARSER,                  // #param
      VALUE_PARSER                    // value
    );

  /** コマンド行全体 */
  private static final Parser<ParsedCommand> COMMAND_PARSER =
    Parser.sequence(
      COMMAND_SYNTAX,
      SPACE_SEP
        .then(COMMAND_ELEMENT.atLeastOnceDelimitedBy(SPACE_SEP, Collectors.toList()))
        .orElse(List.of()),
      (cmd, elements) ->
        new ParsedCommand(cmd, elements)
    );

  // ===========================================================================
  // MARK: ICommandParser実装

  @Override
  public ParsedCommand parse(String text) {
    return COMMAND_PARSER.parse(text);
  }

  /**
   * 入力がパース可能か確認
   */
  public boolean matches(String text) {
    return COMMAND_PARSER.matches(text);
  }
}

