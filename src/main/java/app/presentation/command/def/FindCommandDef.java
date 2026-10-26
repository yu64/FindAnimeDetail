package app.presentation.command.def;

import app.presentation.command.parse.CommandElementReader;
import app.presentation.command.parse.CommandExpression;
import app.usecase.IAnnictClient;
import app.usecase.IInput;
import app.usecase.IInput.FindInput;
import app.usecase.IInput.FindInput.Format;
import app.usecase.IInput.FindInput.Period;
import app.usecase.IInput.FindInput.Status;
import app.usecase.Season;
import app.util.IResult;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
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
      作品名や期間から、アニメの初回放送を検索します。
      検索語ごとに、期間内に見つかったか・放送前か・優先局で放送するかを区別して返します。

      入力例:
      /find #word ぼく
      /find #word ぼく, わたし #season next
      /find #word ぼく #from 2027-01 #to 2027-04
      /find #season 2027-winter #hit #plain
      /find #format md #word ぼく
      /find #word ぼく, わたし #miss

      /find
      ぼく
      わたし

      - word (期間を指定しない場合は必須)
      検索するアニメタイトル。
      カンマ区切り、または2行目以降に1行ずつ複数指定できます。

      - season (任意)
      期間をシーズンで指定します。カンマ区切りで複数指定できます。
      形式: 2027-winter（winter・spring・summer・autumn）、now（今季）、next（来季）
      作品のシーズンは Annict の分類で判定します。

      - from / to (任意)
      期間を日時で指定します。from は含み、to は含みません。
      形式: 2026-10-01T00:00 または 2026-10-01T00:00:00+09:00
      2026-10-01（時刻省略）や2026-10（日・時刻省略）も指定できます。
      省略した日は1日、時刻は00:00、時差は日本時間として扱います。
      初回放送の日時が分からない作品は、期間内か判定できないため出力しません。

      期間は season と from / to のどちらか一方で指定します。
      省略時は、検索実行時以降（from が現在日時）です。
      結果の作品はすべて期間内に収まります。

      - format (任意)
      結果の出力形式。
      パターン: %s
      省略時は TSV です。
      MD は検索結果の表を Kitware Markdown Viewer で開くURLを返します。

      - hit / done / skip / out / miss (任意・値なしのスイッチ)
      出力する状態。指定した状態だけを出力します。省略時はすべて出力します。
      #hit: 放送予定（優先局で、初回放送が現在より後。season 指定では放送日時が未登録の作品も含む）
      #done: 放送済み（優先局で、初回放送が現在以前）
      #skip: 対象外（期間内だが優先局の放送がない）
      #out: 期間外（作品はあるが期間内のものがない。タイトル列に検索語を出します）
      #miss: 未登録（検索語に一致する作品がない。タイトル列に検索語を出します）

      - plain (任意・値なしのスイッチ)
      状態・検索語の列を出力しません。

      補足:
      作品は検索語（期間だけの場合はシーズン）ごとに最大 %d 件、放送データは作品ごとに最大 %d 件まで調べます。
      件数の多い検索語・シーズンでは、一部の作品が結果に含まれません。
      再放送は初回放送の判定に使いません。
      /reserve に貼り付けると、すべての行が予約の対象になります。
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

    var words = wordResult.ok().val().orElse(List.of());

    var fromResult = reader.readTemporal("from", FROM_FORMATTERS,
        () -> "from は年月・日付・日時で指定してください（例: 2026-10、2026-10-01、2026-10-01T00:00）");
    if(fromResult instanceof IResult.Err) return fromResult.err();
    var from = fromResult.ok().val().map(OffsetDateTime::from).orElse(null);

    var toResult = reader.readTemporal("to", FROM_FORMATTERS,
        () -> "to は年月・日付・日時で指定してください（例: 2026-10、2026-10-01、2026-10-01T00:00）");
    if(toResult instanceof IResult.Err) return toResult.err();
    var to = toResult.ok().val().map(OffsetDateTime::from).orElse(null);

    var seasonResult = reader.readStrList("season");
    if(seasonResult instanceof IResult.Err) return seasonResult.err();

    // 期間はシーズンか日時のどちらか一方。どちらもなければ現在以降とし、その場合は検索語を必須にする。
    Period period;
    if(seasonResult.ok().val().isPresent()) {
      if(from != null || to != null) return IResult.err("season と from・to は同時に指定できません");

      var today = LocalDate.now(ZoneId.of("Asia/Tokyo"));
      List<Season> seasons = new ArrayList<>();
      for(String value : seasonResult.ok().val().get()) {
        var season = Season.parse(value, today);
        if(season.isEmpty()) {
          return IResult.err("season は 2027-winter（winter・spring・summer・autumn）・now・next で指定してください: " + value);
        }
        seasons.add(season.get());
      }
      period = new Period.Seasons(seasons);
    } else {
      if(from != null && to != null && !from.isBefore(to)) return IResult.err("from は to より前を指定してください");
      if(words.isEmpty() && from == null && to == null) {
        return IResult.err("word か期間（season・from・to）を指定してください");
      }
      period = new Period.Range(from, to);
    }

    // 指定された状態だけを出力する。1つも指定がなければ FindInput がすべての状態にする。
    Set<Status> statuses = EnumSet.noneOf(Status.class);
    for(var status : Status.values()) {
      var switchResult = reader.readSwitch(status.name().toLowerCase(Locale.ROOT));
      if(switchResult instanceof IResult.Err) return switchResult.err();
      if(switchResult.ok().val().orElse(false)) statuses.add(status);
    }

    var plainResult = reader.readSwitch("plain");
    if(plainResult instanceof IResult.Err) return plainResult.err();

    // コマンド作成
    return IResult.ok(new FindInput(fmt, words, period, statuses, plainResult.ok().val().orElse(false)));
  }
}
