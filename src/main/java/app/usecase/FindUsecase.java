package app.usecase;

import app.config.AnnictConfig;
import app.usecase.IAnnictClient.Anime;
import app.usecase.IAnnictClient.FirstBroadcast;
import app.usecase.IAnnictClient.WordResult;
import app.usecase.IInput.FindInput;
import app.usecase.IInput.FindInput.Status;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
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

  /** 判定済みの1行。work は未登録なら null、reason は対象外のときだけ値を持つ。 */
  private record Row(Status status, String word, Anime work, String reason) {

    /** 初回放送の日本時間。不明ならnull。 */
    ZonedDateTime startsAt() {
      var first = this.work == null ? null : this.work.firstBroadcast();
      return first == null || first.startsAt() == null
          ? null
          : first.startsAt().withZoneSameInstant(ZoneId.of("Asia/Tokyo"));
    }

    /** 局名。不明なら空文字。 */
    String channelName() {
      var first = this.work == null ? null : this.work.firstBroadcast();
      return first == null || first.channelName() == null ? "" : first.channelName().strip();
    }

    /** 未登録の行は、1行1タイトルにそろえるため検索語をタイトルにする。 */
    String title() {
      return this.work == null ? this.word : this.work.title();
    }
  }

  /** アニメ検索クライアントと放送局の優先順位を含む設定を受け取る。 */
  @Inject
  public FindUsecase(IAnnictClient annict, AnnictConfig config) {
    this.annict = annict;
    this.config = config;
  }

  // ###########################################################################
  // MARK: 作品検索・区分の判定

  /** 検索語ごとに作品を区分し、指定された区分だけをTSVまたはMarkdown ViewerのURLで返す。 */
  public String run(FindInput input) {
    var from = input.from() == null ? OffsetDateTime.now(ZoneId.of("Asia/Tokyo")) : input.from();

    // 並び順は検索語の入力順 → 状態（放送予定・対象外・未登録）。
    List<Row> rows = new ArrayList<>();
    for(WordResult result : this.annict.search(input.words(), this.config.channelPriority())) {
      if(result.works().isEmpty()) {
        rows.add(new Row(Status.MISS, result.word(), null, ""));
        continue;
      }

      List<Row> hits = new ArrayList<>();
      List<Row> skips = new ArrayList<>();
      for(Anime work : result.works()) {
        String reason = skipReason(work, from);
        if(reason == null) {
          hits.add(new Row(Status.HIT, result.word(), work, ""));
        } else {
          skips.add(new Row(Status.SKIP, result.word(), work, reason));
        }
      }

      // 同じ曜日・時刻の作品と対象外の作品は、APIの返却順を維持する。
      hits.sort(BROADCAST_ORDER);
      rows.addAll(hits);
      rows.addAll(skips);
    }
    rows.removeIf(row -> !input.statuses().contains(row.status()));

    return input.fmt() == FindInput.Format.MD
        ? toMarkdownViewerUrl(rows, input)
        : toTsv(rows, input.plain());
  }

  /** 放送予定なら null、対象外なら理由を返す。from は境界を含む。 */
  private static String skipReason(Anime work, OffsetDateTime from) {
    FirstBroadcast first = work.firstBroadcast();
    if(first != null && first.startsAt() != null) {
      return first.startsAt().toInstant().isBefore(from.toInstant()) ? "放送済み" : null;
    }
    // 優先局の放送はあるが日時が不明、または放送データ自体がない。
    if(first != null || !work.hasBroadcasts()) return "日時未定";
    return "対象局なし";
  }

  // ###########################################################################
  // MARK: TSVへの整形

  /** 返信全体を1つのTSVにする。0件の場合も列名は返す。 */
  private String toTsv(List<Row> rows, boolean plain) {
    var tsv = new StringJoiner("\n");
    tsv.add(plain
        ? "放送開始日\t時刻\t曜日\t放送局\tタイトル\t公式URL"
        : "状態\t検索語\t放送開始日\t時刻\t曜日\t放送局\tタイトル\t公式URL\t理由");

    for(Row row : rows) {
      var startsAt = row.startsAt();
      var url = row.work() == null || row.work().officialSiteUrl() == null
          ? ""
          : row.work().officialSiteUrl().toString();

      // 値がない欄は空文字にする。値がない理由は状態・理由の列で分かる。
      List<String> cells = new ArrayList<>();
      if(!plain) cells.addAll(List.of(row.status().label, row.word()));
      cells.addAll(List.of(
          startsAt == null ? "" : DATE.format(startsAt),
          startsAt == null ? "" : TIME.format(startsAt),
          startsAt == null ? "" : DAY.format(startsAt),
          row.channelName(), row.title(), url));
      if(!plain) cells.add(row.reason());

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

  /** 区分ごとの表をUTF-8・gzip・Base64の順で変換し、Viewerのmdzパラメータに渡す。 */
  private String toMarkdownViewerUrl(List<Row> rows, FindInput input) {
    var markdown = new StringJoiner("\n");
    markdown.add("# アニメ検索結果").add("");

    // 件数の見出しは、指定された区分だけを並べる。
    var counts = new StringJoiner(" · ");
    for(Status status : input.statuses()) {
      counts.add(status.label + " " + rows.stream().filter(row -> row.status() == status).count() + " 件");
    }
    markdown.add("**" + counts + "** · 日時は日本時間");

    // 指定されていない区分の見出しは出さない。検索語・理由の列は #plain で省く。
    boolean plain = input.plain();
    for(Status status : input.statuses()) {
      var section = rows.stream().filter(row -> row.status() == status).toList();
      markdown.add("").add("## " + status.label).add("");
      if(section.isEmpty()) {
        markdown.add("該当なし");
        continue;
      }

      switch(status) {
        case HIT -> {
          markdown.add(plain ? "| 作品 | 初回放送 | 放送局 | 公式サイト |" : "| 検索語 | 作品 | 初回放送 | 放送局 | 公式サイト |");
          markdown.add(plain ? "| :--- | :--- | :--- | :---: |" : "| :--- | :--- | :--- | :--- | :---: |");
          for(Row row : section) {
            markdown.add(markdownRow(plain, row, workLink(row.work()), broadcastCell(row),
                emptyDash(toMarkdownCell(row.channelName())), officialLink(row.work())));
          }
        }
        case SKIP -> {
          markdown.add(plain ? "| 作品 | 初回放送 | 放送局 |" : "| 検索語 | 作品 | 初回放送 | 放送局 | 理由 |");
          markdown.add(plain ? "| :--- | :--- | :--- |" : "| :--- | :--- | :--- | :--- | :--- |");
          for(Row row : section) {
            var cells = new ArrayList<>(List.of(workLink(row.work()), broadcastCell(row),
                emptyDash(toMarkdownCell(row.channelName()))));
            if(!plain) cells.add(toMarkdownCell(row.reason()));
            markdown.add(markdownRow(plain, row, cells.toArray(String[]::new)));
          }
        }
        case MISS -> section.forEach(row -> markdown.add("- " + toMarkdownCell(row.word())));
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
    if(!plain) all.add(toMarkdownCell(row.word()));
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
