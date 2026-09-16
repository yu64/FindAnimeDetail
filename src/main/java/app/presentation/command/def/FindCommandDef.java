package app.presentation.command.def;

import java.util.List;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

import app.presentation.command.parse.CommandElementReader;
import app.presentation.command.parse.CommandExpression;
import app.usecase.IInput;
import app.usecase.IInput.FindInput;
import app.usecase.IInput.FindInput.Format;
import app.util.IResult;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped 
public class FindCommandDef implements ICommandDef {

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
      /find #format md #word ぼく
      
      /find
      ぼく
      わたし

      - word (必須)
      検索するアニメタイトル。
      カンマ区切りで複数指定できます。

      - from (任意)
      初回放送日時の下限（指定日時を含む）。省略時は検索実行時の現在日時です。
      形式: 2026-10-01T00:00 または 2026-10-01T00:00:00+09:00
      時差の指定がなければ日本時間として扱います。
      放送日時または放送局が不明な作品は結果から除外します。

      - format (任意)
      結果の出力形式。
      パターン: %s
      省略時は TSV です。
      MD は検索結果の表を Kitware Markdown Viewer で開くURLを返します。
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

    var fromResult = reader.readStr("from");
    if(fromResult instanceof IResult.Err) return fromResult.err();
    OffsetDateTime from = null;
    if(fromResult.ok().val().isPresent()) {
      String value = fromResult.ok().val().get();
      try {
        try {
          from = OffsetDateTime.parse(value);
        } catch(DateTimeParseException e) {
          from = LocalDateTime.parse(value).atZone(ZoneId.of("Asia/Tokyo")).toOffsetDateTime();
        }
      } catch(DateTimeParseException e) {
        return IResult.err("from は日時で指定してください（例: 2026-10-01T00:00）");
      }
    }

    // コマンド作成
    return IResult.ok(new FindInput(fmt, word.get(), from));
  }
}
