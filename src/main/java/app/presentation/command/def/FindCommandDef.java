package app.presentation.command.def;

import java.util.List;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.Locale;

import app.presentation.command.parse.CommandElementReader;
import app.presentation.command.parse.CommandExpression;
import app.usecase.IInput;
import app.usecase.IInput.FindInput;
import app.usecase.IInput.FindInput.Format;
import app.util.IResult;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped 
public class FindCommandDef implements ICommandDef {

  /** 日・時刻・時差の省略値を補完し、どの形式もOffsetDateTimeへ変換できるようにする。 */
  private static final List<DateTimeFormatter> FROM_FORMATTERS = List.of(
    DateTimeFormatter.ISO_OFFSET_DATE_TIME,
    DateTimeFormatter.ISO_LOCAL_DATE_TIME,
    DateTimeFormatter.ISO_LOCAL_DATE,
    DateTimeFormatter.ofPattern("uuuu-MM", Locale.ROOT)
  ).stream().map(format -> new DateTimeFormatterBuilder()
    .append(format)
    .parseDefaulting(ChronoField.DAY_OF_MONTH, 1)
    .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
    .parseDefaulting(ChronoField.OFFSET_SECONDS, ZoneOffset.ofHours(9).getTotalSeconds())
    .toFormatter(Locale.ROOT)
    .withResolverStyle(ResolverStyle.STRICT)
  ).toList();

  @Override
  public String name() {
    return "find";
  }

  @Override
  public String help() {
    return """
      [/find]
      アニメ情報を検索します。

      入力例:
      /find #word ぼく
      /find #word ぼく #from 2026-10-01T00:00
      /find #word ぼく #from 2026-10
      /find #format md #word ぼく
      /find #word ぼく #complete
      
      /find
      ぼく
      わたし

      - format (任意)
      結果の出力形式。
      パターン: %s
      省略時は TSV です。
      MD は検索結果の表を Kitware Markdown Viewer で開くURLを返します。

      - word (必須)
      検索するアニメタイトル。
      カンマ区切りで複数指定できます。
      複数の検索語はOR条件で検索します。

      - from (任意)
      初回放送日時の下限（指定日時を含む）。省略時は検索実行時の現在日時です。
      形式: 2026-10-01T00:00 または 2026-10-01T00:00:00+09:00
      2026-10-01（時刻省略）や2026-10（日・時刻省略）も指定できます。
      省略した日は1日、時刻は00:00として扱います。
      時差の指定がなければ日本時間として扱います。
      放送日時・放送局の不明な欄はUnknowと表示します。日時不明の作品は日時条件でも残します。

      - complete (任意・値なしのスイッチ)
      #complete を指定すると、放送日時・放送局が揃った作品だけを表示します。
      公式URLの有無は判定に含めません。省略時は不明な欄をUnknowとして表示します。
      """.formatted(List.of(Format.values()).toString());
  }

  @Override
  public IResult<IInput, String> map(CommandExpression cmd)
  {
    CommandElementReader reader = new CommandElementReader(cmd);

    // 各引数の読み取り・型チェック
    var fmtResult = reader.readEnum("format", Format.class);
    if(fmtResult instanceof IResult.Err) return fmtResult.err();

    var wordResult = reader.readStrList("word");
    if(wordResult instanceof IResult.Err) return wordResult.err();
    
    // 各引数のデフォルトと必須確認
    var fmt = fmtResult.ok().val()
      .orElse(Format.TSV);
    
    var word = wordResult.ok().val();
    if(word.isEmpty()) return IResult.err("word は必須です");

    var fromResult = reader.readTemporal("from", FROM_FORMATTERS,
      () -> "from は年月・日付・日時で指定してください（例: 2026-10、2026-10-01、2026-10-01T00:00）");
    if(fromResult instanceof IResult.Err) return fromResult.err();
    var from = fromResult.ok().val().map(OffsetDateTime::from).orElse(null);

    var completeResult = reader.readSwitch("complete");
    if(completeResult instanceof IResult.Err) return completeResult.err();

    // コマンド作成
    return IResult.ok(new FindInput(fmt, word.get(), from, completeResult.ok().val().orElse(false)));
  }
}
