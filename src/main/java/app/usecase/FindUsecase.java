package app.usecase;

import app.config.AnnictConfig;
import app.usecase.IAnnictClient.Anime;
import app.usecase.IAnnictClient.FirstBroadcast;
import app.usecase.IAnnictClient.WordResult;
import app.usecase.IInput.FindInput;
import app.usecase.IInput.FindInput.Period;
import app.usecase.IInput.FindInput.Status;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.zip.GZIPOutputStream;

@ApplicationScoped
public class FindUsecase {

  // ###########################################################################
  // MARK: 依存オブジェクト・出力形式

  private final IAnnictClient annict;
  private final AnnictConfig config;
  private final Clock clock;

  private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd");
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
  // Native Imageに含まれるロケールデータに依存せず、日本語の曜日を表示する。
  private static final DateTimeFormatter DAY = new DateTimeFormatterBuilder()
      .appendText(ChronoField.DAY_OF_WEEK,
          Map.of(1L, "月", 2L, "火", 3L, "水", 4L, "木", 5L, "金", 6L, "土", 7L, "日"))
      .toFormatter(Locale.ROOT);

  // 日付は比較せず、日本時間の曜日（月→日）と時刻で並べる。
  private static final Comparator<Row> BROADCAST_ORDER = Comparator.comparing(
      Row::startsAt,
      Comparator.nullsLast(Comparator.comparingInt((ZonedDateTime time) -> time.getDayOfWeek().getValue())
          .thenComparing(time -> time.toLocalTime())));

  /** 判定済みの1行。work は期間外・未登録なら null、word は期間だけで探した場合は null。 */
  private record Row(Status status, String word, Anime work) {

    /** 優先局での初回放送の日本時間。不明ならnull。 */
    ZonedDateTime startsAt() {
      var first = this.work == null ? null : this.work.firstBroadcast();
      return first == null || first.startsAt() == null ? null : first.startsAt().withZoneSameInstant(JST);
    }

    /** 局名。不明なら空文字。 */
    String channelName() {
      var first = this.work == null ? null : this.work.firstBroadcast();
      return first == null || first.channelName() == null ? "" : first.channelName().strip();
    }

    /** 期間外・未登録の行は、1行1タイトルにそろえるため検索語をタイトルにする。 */
    String title() {
      return this.work == null ? this.word : this.work.title();
    }

    String wordOrEmpty() {
      return this.word == null ? "" : this.word;
    }
  }

  /** アニメ検索クライアントと放送局の優先順位を含む設定を受け取る。 */
  @Inject
  public FindUsecase(IAnnictClient annict, AnnictConfig config) {
    this(annict, config, Clock.systemUTC());
  }

  /** 現在日時を固定して判定を確認できるよう、時計を受け取る。 */
  FindUsecase(IAnnictClient annict, AnnictConfig config, Clock clock) {
    this.annict = annict;
    this.config = config;
    this.clock = clock;
  }

  // ###########################################################################
  // MARK: 作品検索・期間と状態の判定

  /** 期間内の作品を状態ごとに分け、指定された状態だけをTSVまたはMarkdown ViewerのURLで返す。 */
  public String run(FindInput input) {
    // 判定中に時刻が進んでも、すべての作品を同じ「現在」で判定する。
    var now = this.clock.instant();

    // 期間の指定がなければ、今季と来季とする。
    var today = Season.of(now.atZone(JST).toLocalDate());
    var period = input.period() != null ? input.period() : new Period.Seasons(List.of(today, today.next()));

    // シーズン指定は Annict に絞り込ませる。日時だけの指定は前後のシーズンを含めて問い合わせ、日時で絞り込む。
    List<String> seasons = switch(period) {
      case Period.Seasons s -> s.seasons().stream().map(Season::toString).toList();
      case Period.Range r -> input.words().isEmpty() ? coveringSeasons(r, now) : List.of();
    };

    // 並び順は検索語の入力順 → 状態（放送予定・放送済み・対象外・期間外・未登録）。
    List<Row> rows = new ArrayList<>();
    for(WordResult result : this.annict.search(input.words(), seasons, this.config.channelPriority())) {
      Map<Status, List<Row>> byStatus = new EnumMap<>(Status.class);
      for(Anime work : result.works()) {
        Status status = classify(work, period, seasons, now);
        if(status != null) {
          byStatus.computeIfAbsent(status, key -> new ArrayList<>()).add(new Row(status, result.word(), work));
        }
      }

      // 同じ曜日・時刻の作品と対象外の作品は、APIの返却順を維持する。
      byStatus.getOrDefault(Status.HIT, new ArrayList<>()).sort(BROADCAST_ORDER);
      byStatus.getOrDefault(Status.DONE, new ArrayList<>()).sort(BROADCAST_ORDER);
      byStatus.values().forEach(rows::addAll);

      // 期間内の作品がない検索語は、作品の有無で期間外か未登録の1行にする。
      if(byStatus.isEmpty() && result.word() != null) {
        rows.add(new Row(result.exists() ? Status.OUT : Status.MISS, result.word(), null));
      }
    }
    rows.removeIf(row -> !input.statuses().contains(row.status()));

    return input.fmt() == FindInput.Format.MD
        ? toMarkdownViewerUrl(rows, input)
        : toTsv(rows, input.plain());
  }

  /** 期間内なら状態を、期間外なら null を返す。 */
  private static Status classify(Anime work, Period period, List<String> seasons, Instant now) {
    FirstBroadcast first = work.firstBroadcast();

    // シーズン指定は作品のシーズンで期間を判定する。放送日時が未登録でも、そのシーズンの作品は放送予定とする。
    if(period instanceof Period.Seasons) {
      if(work.season() == null || !seasons.contains(work.season())) return null;
      if(first == null) return work.hasBroadcasts() ? Status.SKIP : Status.HIT;
      if(first.startsAt() == null) return Status.HIT;
      return first.startsAt().toInstant().isAfter(now) ? Status.HIT : Status.DONE;
    }

    // 日時指定は初回放送の日時で期間を判定する。日時が分からない作品は期間内か判定できないため出力しない。
    var range = (Period.Range) period;
    if(first != null) {
      if(first.startsAt() == null || !within(first.startsAt(), range)) return null;
      return first.startsAt().toInstant().isAfter(now) ? Status.HIT : Status.DONE;
    }
    return work.firstAiredAt() != null && within(work.firstAiredAt(), range) ? Status.SKIP : null;
  }

  /** from は境界を含み、to は含まない。指定のない側は制限しない。 */
  private static boolean within(ZonedDateTime time, Period.Range range) {
    var instant = time.toInstant();
    return (range.from() == null || !instant.isBefore(range.from().toInstant()))
        && (range.to() == null || instant.isBefore(range.to().toInstant()));
  }

  /**
   * 日時の期間にかかるシーズンと前後1シーズンずつ。to がなければ来季までとする。
   * from がないと問い合わせるシーズンを決められないため、検索語のない日時の期間では from を必須とする。
   */
  private static List<String> coveringSeasons(Period.Range range, Instant now) {
    if(range.from() == null) {
      throw new IllegalArgumentException("Specify from when searching by date range without titles");
    }
    var first = Season.of(range.from().atZoneSameInstant(JST).toLocalDate()).previous();
    var last = range.to() == null
        ? Season.of(now.atZone(JST).toLocalDate()).next()
        : Season.of(range.to().toInstant().minusNanos(1).atZone(JST).toLocalDate()).next();
    if(first.isAfter(last)) last = first.next().next();

    List<String> seasons = new ArrayList<>();
    for(var season = first; !season.isAfter(last); season = season.next()) {
      seasons.add(season.toString());
    }
    return seasons;
  }

  // ###########################################################################
  // MARK: TSVへの整形

  /** 返信全体を1つのTSVにする。0件の場合も列名は返す。 */
  private String toTsv(List<Row> rows, boolean plain) {
    var tsv = new StringJoiner("\n");
    tsv.add(plain
        ? "放送開始日\t時刻\t曜日\t放送局\tタイトル\t公式URL"
        : "状態\t検索語\t放送開始日\t時刻\t曜日\t放送局\tタイトル\t公式URL");

    for(Row row : rows) {
      var startsAt = row.startsAt();
      var url = row.work() == null || row.work().officialSiteUrl() == null
          ? ""
          : row.work().officialSiteUrl().toString();

      // 値がない欄は空文字にする。値がない理由は状態の列で分かる。
      List<String> cells = new ArrayList<>();
      if(!plain) cells.addAll(List.of(row.status().label, row.wordOrEmpty()));
      cells.addAll(List.of(
          startsAt == null ? "" : DATE.format(startsAt),
          startsAt == null ? "" : TIME.format(startsAt),
          startsAt == null ? "" : DAY.format(startsAt),
          row.channelName(), row.title(), url));

      tsv.add(String.join("\t", cells.stream().map(this::toTsvCell).toList()));
    }

    return tsv.toString();
  }

  /** タブと改行を空白へ置き換え、外部データによるTSVの列・行の崩れを防ぐ。 */
  private String toTsvCell(String value) {
    return value.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
  }

  // ###########################################################################
  // MARK: Markdown Viewerへの整形

  /** 状態ごとの表をUTF-8・gzip・Base64の順で変換し、Viewerのmdzパラメータに渡す。 */
  private String toMarkdownViewerUrl(List<Row> rows, FindInput input) {
    var markdown = new StringJoiner("\n");
    markdown.add("# アニメ検索結果").add("");

    // 件数の見出しは、指定された状態だけを並べる。
    var counts = new StringJoiner(" · ");
    for(Status status : input.statuses()) {
      counts.add(status.label + " " + rows.stream().filter(row -> row.status() == status).count() + " 件");
    }
    markdown.add("**" + counts + "** · 日時は日本時間");

    // 指定されていない状態の見出しは出さない。検索語の列は #plain で省く。
    boolean plain = input.plain();
    for(Status status : input.statuses()) {
      var section = rows.stream().filter(row -> row.status() == status).toList();
      markdown.add("").add("## " + status.label).add("");
      if(section.isEmpty()) {
        markdown.add("該当なし");
        continue;
      }

      switch(status) {
        case HIT, DONE -> {
          markdown.add(plain ? "| 作品 | 初回放送 | 放送局 | 公式サイト |" : "| 検索語 | 作品 | 初回放送 | 放送局 | 公式サイト |");
          markdown.add(plain ? "| :--- | :--- | :--- | :---: |" : "| :--- | :--- | :--- | :--- | :---: |");
          for(Row row : section) {
            markdown.add(markdownRow(plain, row, workLink(row.work()), broadcastCell(row),
                emptyDash(toMarkdownCell(row.channelName())), officialLink(row.work())));
          }
        }
        case SKIP -> {
          markdown.add(plain ? "| 作品 | 初回放送 | 放送局 |" : "| 検索語 | 作品 | 初回放送 | 放送局 |");
          markdown.add(plain ? "| :--- | :--- | :--- |" : "| :--- | :--- | :--- | :--- |");
          for(Row row : section) {
            markdown.add(markdownRow(plain, row, workLink(row.work()), broadcastCell(row),
                emptyDash(toMarkdownCell(row.channelName()))));
          }
        }
        case OUT, MISS -> section.forEach(row -> markdown.add("- " + toMarkdownCell(row.wordOrEmpty())));
      }
    }

    // 日本語をUTF-8に変換してgzip圧縮し、URLへ格納するデータを小さくする。
    var compressed = new ByteArrayOutputStream();
    try (var gzip = new GZIPOutputStream(compressed)) {
      gzip.write(markdown.toString().getBytes(StandardCharsets.UTF_8));
    } catch(IOException e) {
      throw new UncheckedIOException("Could not compress Markdown", e);
    }

    // 圧縮したバイト列をBase64化し、クエリパラメータとして安全に渡せる形にする。
    String url = "https://kitware.github.io/markdown-viewer/?mdz="
        + URLEncoder.encode(Base64.getEncoder().encodeToString(compressed.toByteArray()), StandardCharsets.UTF_8);

    // LINEの1メッセージに収まるURLだけを返す。検索結果は切り捨てない。
    return url.length() <= 5000
        ? url
        : "検索結果が多いため表示用URLを作成できません。タイトルや日時を指定して検索範囲を絞ってください。";
  }

  /** 表の1行。#plain でなければ先頭に検索語の列を置く。 */
  private String markdownRow(boolean plain, Row row, String... cells) {
    var all = new ArrayList<String>();
    if(!plain) all.add(emptyDash(toMarkdownCell(row.wordOrEmpty())));
    all.addAll(List.of(cells));
    return "| " + String.join(" | ", all) + " |";
  }

  private String workLink(Anime work) {
    return "[" + toMarkdownCell(work.title()) + "](https://annict.com/works/" + work.annictId() + ")";
  }

  private String broadcastCell(Row row) {
    var startsAt = row.startsAt();
    return startsAt == null ? "—" : DATE.format(startsAt) + "（" + DAY.format(startsAt) + "） " + TIME.format(startsAt);
  }

  /** HTTP(S)の公式URLはリンクにし、それ以外は文字列、未登録はダッシュにする。 */
  private String officialLink(Anime work) {
    var url = work.officialSiteUrl();
    if(url == null) return "—";
    if("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme())) {
      return "[公式サイト](<" + url.toASCIIString().replace("|", "%7C") + ">)";
    }
    return toMarkdownCell(url.toString());
  }

  private static String emptyDash(String value) {
    return value.isEmpty() ? "—" : value;
  }

  /** 外部の文字列を表のセル内の文字として表示し、Markdown構文として解釈させない。 */
  private String toMarkdownCell(String value) {
    // 改行・タブを除いた文字列を走査し、MarkdownのASCII記号をエスケープする。
    var escaped = new StringBuilder();
    for(char ch : toTsvCell(value).toCharArray()) {
      if((ch >= '!' && ch <= '/') || (ch >= ':' && ch <= '@')
          || (ch >= '[' && ch <= '`') || (ch >= '{' && ch <= '~')) {
        escaped.append('\\');
      }
      escaped.append(ch);
    }

    return escaped.toString();
  }
}
