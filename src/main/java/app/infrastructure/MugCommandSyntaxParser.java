package app.infrastructure;

import app.presentation.command.parse.ICommandSyntaxParser;
import app.presentation.command.parse.CommandExpression;
import app.presentation.command.parse.CommandExpression.IElement;
import app.presentation.command.parse.CommandExpression.IElement.*;
import app.util.IResult;
import com.google.common.labs.parse.Parser;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** docs/parser.md の構文を MUG のパーサーコンビネーターで解析する。 */
@ApplicationScoped
public class MugCommandSyntaxParser implements ICommandSyntaxParser {

  // ###########################################################################
  // MARK: 基本要素

  // 改行は行構造に意味を持つため、行内の空白とは別に扱う。
  private static final Parser<String> LF = Parser.anyOf("\r\n", "\r", "\n");
  private static final Parser<String> SPACE = Parser.consecutive("[ \t\u3000]");
  private static final Parser<String>.OrEmpty SPACES = SPACE.orElse("");
  private static final Parser<String> NAME = Parser.consecutive(
    c -> Character.isLetterOrDigit(c) || c == '_', "名称"
  );
  private static final Parser<String> LINE_TEXT = Parser.consecutive("[^\r\n]");

  private static boolean isSpace(char c) {
    return c == ' ' || c == '\t' || c == '\u3000';
  }

  private static boolean isNewline(char c) {
    return c == '\r' || c == '\n';
  }

  /** 区切りは消費せず検証し、次のパーサーに渡す。 */
  private static <T> Parser<T> valueBoundary(Parser<T> parser) {
    return parser.notImmediatelyFollowedBy(
      c -> !isSpace(c) && !isNewline(c) && c != ',', "値の後には区切りが必要です");
  }

  /** 行末の空白を消費し、改行または入力終端まで読み切ったことを確認する。 */
  private static <T> Parser<T> lineEnd(Parser<T> parser) {
    return parser.followedBy(SPACES)
      .notImmediatelyFollowedBy(c -> !isNewline(c), "行末が必要です");
  }

  // ###########################################################################
  // MARK: 引用文字列・エスケープ・値

  // 引用符を重ねたエスケープを先に読む。引用の中でも改行は許可しない。
  private static final Parser<String> DOUBLE_QUOTED = valueBoundary(
    Parser.anyOf(
      Parser.string("\"\"").thenReturn("\""),
      Parser.consecutive("[^\"\r\n]")
    ).zeroOrMore(Collectors.joining()).between("\"", "\"")
  );

  /** 空白直後の単独 # は次の引数。## は引用文字列内のエスケープ。 */
  private static final Parser<String> HASH_QUOTED = valueBoundary(
    Parser.anyOf(
      Parser.string("##").thenReturn("#"),
      Parser.consecutive("[^# \t\u3000\r\n]"),
      Parser.one("[ \t\u3000]")
        .notFollowedBy(Parser.one('#').notFollowedBy("#"), "次の引数")
        .source()
    ).zeroOrMore(Collectors.joining()).between("#", "#")
  );

  private static final Parser<String> QUOTED = Parser.anyOf(DOUBLE_QUOTED, HASH_QUOTED);
  private static final Parser<String> VALUE = Parser.anyOf(
    QUOTED, Parser.consecutive("[^ \t\u3000\r\n,#\"]")
  );
  // 値は1個でも成立する。単一値かフラットリストかは呼び出し側で決める。
  private static final Parser<List<String>> VALUES = VALUE.atLeastOnceDelimitedBy(
    Parser.one(',').between(SPACES, SPACES), Collectors.toList()
  );

  // ###########################################################################
  // MARK: パラメータ・フラットリスト・スイッチ

  private static final Parser<String> PARAM_NAME = Parser.one('#').then(NAME)
    .notImmediatelyFollowedBy(c -> !isSpace(c) && !isNewline(c), "パラメータ名の後には空白が必要です");

  private static final Parser<IElement> NAMED_PARAM = Parser.sequence(
    PARAM_NAME, SPACE.then(VALUES),
    (name, values) -> values.size() == 1
      ? new ParamElement(name, values.getFirst()) : new FlatListElement(name, values)
  );

  private static final Parser<IElement> QUASI_PARAM = Parser.one('#').then(SPACE).then(VALUES)
    .map(values -> values.size() == 1
      ? new QuasiUnnamedParamElement(values.getFirst()) : new QuasiUnnamedFlatListElement(values));

  private static final Parser<IElement> SWITCH = PARAM_NAME.map(SwitchElement::new);
  // 値を持つ構文を先に試し、名前だけが残る場合にスイッチとして読む。
  private static final Parser<IElement> NON_UNNAMED = Parser.anyOf(NAMED_PARAM, QUASI_PARAM, SWITCH);
  private static final Parser<IElement> UNNAMED = VALUES.map(values -> values.size() == 1
    ? new ValueElement(values.getFirst()) : new UnnamedFlatListElement(values));

  // #...# の引用が成立する場合は #名称 より優先する。
  private static final Parser<IElement> ELEMENT = Parser.anyOf(UNNAMED, NON_UNNAMED);
  private static final Parser<List<IElement>> ARGUMENTS =
    ELEMENT.atLeastOnceDelimitedBy(SPACE, Collectors.toList());

  // ###########################################################################
  // MARK: 行頭の構文・リストの境界

  // 行の種類を保持し、後段で連続する値の行をひとつのリストにまとめる。
  private sealed interface BodyLine {}
  private record BlankLine() implements BodyLine {}
  private record HeaderLine(String name) implements BodyLine {}
  private record ValueLine(String value) implements BodyLine {}
  private record ArgumentLine(List<IElement> elements) implements BodyLine {}

  // 引用で始まる行もリストの値。引用後の文字列は引数として分解しない。
  private static final Parser<BodyLine> QUOTED_LINE = Parser.sequence(
    QUOTED, LINE_TEXT.orElse(""), (value, rest) -> new ValueLine(value + rest)
  );
  // #名称だけの行は、後続の値の有無でリスト見出しかスイッチかを決める。
  private static final Parser<BodyLine> HEADER_LINE = lineEnd(
    Parser.one('#').then(NAME.orElse("")).map(HeaderLine::new)
  ).map(header -> header);

  private static final Parser<BodyLine> ARGUMENT_LINE = lineEnd(Parser.sequence(
    NON_UNNAMED, SPACE.then(ARGUMENTS).orElse(List.of()),
    (first, rest) -> {
      // 行頭の非無名引数と、それ以降の引数を入力順に並べる。
      List<IElement> elements = new ArrayList<>();
      elements.add(first);
      elements.addAll(rest);

      return (BodyLine) new ArgumentLine(elements);
    }
  ));

  /** 通常のリスト行では、行途中の空白・カンマ・#も値の一部。 */
  private static final Parser<BodyLine> VALUE_LINE = Parser.sequence(
    Parser.one("[^#\" \t\u3000\r\n]"), LINE_TEXT.orElse(""),
    (first, rest) -> new ValueLine(first + rest)
  );

  // インデントを除き、引用・見出し・引数行を優先して判定する。
  // 空行はリストの境界になるため、読み飛ばさず後段に渡す。
  private static final Parser<BodyLine>.OrEmpty BODY_LINE = SPACES.then(
    Parser.anyOf(QUOTED_LINE, HEADER_LINE, ARGUMENT_LINE, VALUE_LINE).orElse(new BlankLine())
  );

  // ###########################################################################
  // MARK: コマンド全体

  private static final Parser<String> COMMAND_NAME = Parser.sequence(
    Parser.anyOf(Parser.one('/'), Parser.one('$'), Parser.one('@')), NAME,
    (prefix, name) -> name
  );
  // 先頭行の引数は行内の構文として読む。末尾の #名称はスイッチになる。
  private static final Parser<CommandExpression> COMMAND_LINE = lineEnd(Parser.sequence(
    COMMAND_NAME, SPACE.then(ARGUMENTS).orElse(List.of()), CommandExpression::new
  ));
  // 先頭行と後続行を別の規則で読み、解析済みの行を assemble でまとめる。
  private static final Parser<CommandExpression> DOCUMENT = Parser.sequence(
    COMMAND_LINE, LF.then(BODY_LINE).zeroOrMore(), MugCommandSyntaxParser::assemble
  );

  /** MUG が解析した行をまとめる。ここでは文字列の再解析を行わない。 */
  private static CommandExpression assemble(CommandExpression command, List<BodyLine> lines) {
    // コマンド行の引数を先に保持し、後続行の要素を入力順に追加する。
    List<IElement> elements = new ArrayList<>(command.elements());

    for (int index = 0; index < lines.size();) {
      // index は次に処理する行を指す。リストをまとめた分もここから先へ進める。
      BodyLine line = lines.get(index++);

      // 空行はリストの境界として使い、コマンド要素には追加しない。
      if (line instanceof BlankLine) continue;

      // 引数行は解析済みの要素をそのまま追加し、次の行へ進む。
      if (line instanceof ArgumentLine argument) {
        elements.addAll(argument.elements());
        continue;
      }

      // 見出し行または最初の値の行から、連続する値の行だけを集める。
      // 空行・別の見出し・引数行に達したら止め、その行は次の反復で処理する。
      List<String> values = new ArrayList<>();
      if (line instanceof ValueLine value) values.add(value.value());

      while (index < lines.size() && lines.get(index) instanceof ValueLine value) {
        values.add(value.value());
        index++;
      }

      // 見出しの有無と名称に応じて、リストの種類を確定する。
      if (line instanceof HeaderLine header) {
        if (header.name().isEmpty()) {
          // # だけではスイッチにならないため、値がなければ構文エラー。
          if (values.isEmpty()) throw new EmptyListException();

          // 名前のない見出しに続く値を、準無名リストとして保持する。
          elements.add(new QuasiUnnamedListElement(values));
        } else {
          // #名称に値の行が続かなければ、名前だけのスイッチとして扱う。
          elements.add(values.isEmpty() ? new SwitchElement(header.name()) : new ListElement(header.name(), values));
        }
      } else {
        // 見出しなしで始まった値の行は、無名リストとして保持する。
        elements.add(new UnnamedListElement(values));
      }
    }

    // 先頭行と後続行の要素を、入力順を保った1つのコマンドにまとめる。
    return new CommandExpression(command.command(), elements);
  }

  private static final class EmptyListException extends RuntimeException {
    EmptyListException() {
      super("準無名リストには値の行が必要です。");
    }
  }

  // ###########################################################################
  // MARK: ICommandParser 実装

  @Override
  public IResult<CommandExpression, String> parse(String text) {
    // MUG に渡せない null は、ほかの入力エラーと同じ失敗結果にする。
    if (text == null) return IResult.err("コマンドを入力してください。");

    // 全入力を解析する。構文エラーと空の準無名リストを呼び出し側へ返す。
    try {
      return IResult.ok(DOCUMENT.parse(text));
    } catch (Parser.ParseException | EmptyListException ex) {
      return IResult.err(ex.getMessage());
    }
  }
}
