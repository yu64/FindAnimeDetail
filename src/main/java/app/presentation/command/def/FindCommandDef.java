package app.presentation.command.def;

import app.presentation.command.parse.CommandElementReader;
import app.presentation.command.parse.CommandExpression;
import app.usecase.IAnnictClient;
import app.usecase.IInput;
import app.usecase.IInput.FindInput;
import app.usecase.IInput.FindInput.Format;
import app.usecase.IInput.FindInput.Status;
import app.util.IResult;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

@ApplicationScoped
public class FindCommandDef implements ICommandDef {

  /** from に指定できる形式。どの形式もOffsetDateTimeへ変換できるようにする。 */
  private static final List<DateTimeFormatter> FROM_FORMATTERS = Stream.of(
      DateTimeFormatter.ISO_OFFSET_DATE_TIME,
      DateTimeFormatter.ISO_LOCAL_DATE_TIME,
      DateTimeFormatter.ISO_LOCAL_DATE,
      DateTimeFormatter.ofPattern("uuuu-MM", Locale.ROOT))
      .map(FindCommandDef::withDefaults)
      .toList();

  /** 日・時刻・時差の省略値を補完する。 */
  private static DateTimeFormatter withDefaults(DateTimeFormatter format) {
    return new DateTimeFormatterBuilder()
        .append(format)
        .parseDefaulting(ChronoField.DAY_OF_MONTH, 1)
        .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
        .parseDefaulting(ChronoField.OFFSET_SECONDS, ZoneOffset.ofHours(9).getTotalSeconds())
        .toFormatter(Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT);
  }

  @Override
  public String name() {
    return "find";
  }

  @Override
  public String help() {
    return """
      [/find]
      これから初回放送されるアニメを検索します。
      検索語ごとに、放送予定・対象外・未登録を区別して返します。

      入力例:
      /find #word ぼく
      /find #word ぼく, わたし #from 2026-10
      /find #format md #word ぼく
      /find #word ぼく, わたし #miss
      /find #word ぼく #hit #plain

      /find
      ぼく
      わたし

      - word (必須)
      検索するアニメタイトル。
      カンマ区切り、または2行目以降に1行ずつ複数指定できます。

      - from (任意)
      初回放送日時の下限（指定日時を含む）。省略時は検索実行時の現在日時です。
      形式: 2026-10-01T00:00 または 2026-10-01T00:00:00+09:00
      2026-10-01（時刻省略）や2026-10（日・時刻省略）も指定できます。
      省略した日は1日、時刻は00:00として扱います。
      時差の指定がなければ日本時間として扱います。

      - format (任意)
      結果の出力形式。
      パターン: %s
      省略時は TSV です。
      MD は検索結果の表を Kitware Markdown Viewer で開くURLを返します。

      - hit / skip / miss (任意・値なしのスイッチ)
      出力する区分。指定した区分だけを出力します。省略時はすべて出力します。
      #hit: 放送予定（優先局での初回放送が from 以降）
      #skip: 対象外（登録はあるが条件に合わない）
      #miss: 未登録（検索語に一致する作品がない。タイトル列に検索語を出します）

      対象外の理由:
      放送済み: 優先局での初回放送が from より前
      日時未定: 放送データがない、または日時が不明
      対象局なし: 調べた放送データに優先局の放送がない

      - plain (任意・値なしのスイッチ)
      状態・検索語・理由の列を出力しません。

      補足:
      作品は検索語ごとに最大 %d 件、放送データは作品ごとに最大 %d 件まで調べます。
      件数の多い検索語では、一部の作品が結果に含まれません。
      再放送は初回放送の判定に使いません。
      /reserve に貼り付けると、対象外の作品と未登録の検索語も予約の対象になります。
      放送予定だけを予約する場合は #hit で絞ってください。
      """.formatted(List.of(Format.values()).toString(), IAnnictClient.PAGE_SIZE, IAnnictClient.PAGE_SIZE);
  }

  @Override
  public IResult<IInput, String> map(CommandExpression cmd) {
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

    // 指定された区分だけを出力する。1つも指定がなければ FindInput がすべての区分にする。
    Set<Status> statuses = EnumSet.noneOf(Status.class);
    for(var status : Status.values()) {
      var switchResult = reader.readSwitch(status.name().toLowerCase(Locale.ROOT));
      if(switchResult instanceof IResult.Err) return switchResult.err();
      if(switchResult.ok().val().orElse(false)) statuses.add(status);
    }

    var plainResult = reader.readSwitch("plain");
    if(plainResult instanceof IResult.Err) return plainResult.err();

    // コマンド作成
    return IResult.ok(new FindInput(fmt, word.get(), from, statuses, plainResult.ok().val().orElse(false)));
  }
}
