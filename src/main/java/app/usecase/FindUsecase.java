package app.usecase;

import app.config.AnnictConfig;
import app.usecase.IAnnictClient.Anime;
import app.usecase.IAnnictClient.SearchCondition;
import app.usecase.IInput.FindInput;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.StringJoiner;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
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
  private static final DateTimeFormatter DAY = new java.time.format.DateTimeFormatterBuilder()
    .appendText(java.time.temporal.ChronoField.DAY_OF_WEEK,
      java.util.Map.of(1L, "月", 2L, "火", 3L, "水", 4L, "木", 5L, "金", 6L, "土", 7L, "日"))
    .toFormatter(java.util.Locale.ROOT);

  // 日付は比較せず、日本時間の曜日（月→日）と時刻で並べる。日時不明は末尾。
  private static final Comparator<Anime> BROADCAST_ORDER = Comparator.comparing(
    FindUsecase::japaneseStartsAt,
    Comparator.nullsLast(Comparator.comparingInt((ZonedDateTime time) -> time.getDayOfWeek().getValue())
      .thenComparing(time -> time.toLocalTime())));

  /** アニメ検索クライアントと放送局の優先順位を含む設定を受け取る。 */
  @Inject
  public FindUsecase(IAnnictClient annict, AnnictConfig config)
  {
    this.annict = annict;
    this.config = config;
  }

  // ###########################################################################
  // MARK: 作品検索

  /** 全タイトルを検索し、重複を除いた結果をTSVまたはMarkdown ViewerのURLで返す。 */
  public String run(FindInput input)
  {
    var from = input.from() == null ? OffsetDateTime.now(ZoneId.of("Asia/Tokyo")) : input.from();
    var condition = new SearchCondition(input.words(), List.of(), from, false);
    // 全タイトルをOR検索し、APIの返却順を保ちながら重複を除く。
    var works = new LinkedHashMap<Integer, Anime>();
    for (Anime work : annict.search(condition, config.channelPriority())) {
      var first = work.firstBroadcast();
      if (!input.all() && (first == null || first.startsAt() == null
          || first.channelName() == null || first.channelName().isBlank())) {
        continue;
      }
      works.putIfAbsent(work.annictId(), work);
    }

    // 同じ曜日・時刻の作品と日時不明の作品同士は、APIの返却順を維持する。
    var sortedWorks = works.values().stream().sorted(BROADCAST_ORDER).toList();

    // Markdown指定なら、表をブラウザで開くためのURLを返す。
    if (input.fmt() == FindInput.Format.MD) {
      return toMarkdownViewerUrl(sortedWorks);
    }

    // TSVはヘッダーに続けて作品を並べる。0件の場合も列名は返す。
    var tsv = new StringJoiner("\n");
    tsv.add("放送開始日\t時刻\t曜日\t放送局\tタイトル\t公式URL");

    for (Anime work : sortedWorks) {
      tsv.add(toTsvRow(work));
    }

    return tsv.toString();
  }

  private static ZonedDateTime japaneseStartsAt(Anime work) {
    var first = work.firstBroadcast();
    return first == null || first.startsAt() == null ? null
      : first.startsAt().withZoneSameInstant(ZoneId.of("Asia/Tokyo"));
  }

  // ###########################################################################
  // MARK: Markdown Viewerへの整形

  /** Markdown表をUTF-8・gzip・Base64の順で変換し、Viewerのmdzパラメータに渡す。 */
  private String toMarkdownViewerUrl(Collection<Anime> works)
  {
    // タイトルを先頭に置き、日時を1列にまとめて横幅を抑える。
    var markdown = new StringJoiner("\n");
    markdown.add("# アニメ検索結果").add("");
    markdown.add("**" + works.size() + " 作品** · 日時は日本時間").add("");
    markdown.add("> N/A：作品の存在は確認できましたが、該当する放送情報は不明です。").add("");
    markdown.add("| 作品 | 初回放送 | 放送局 | 公式サイト |");
    markdown.add("| :--- | :--- | :--- | :---: |");

    // 作品ごとに表示用の日時・リンクを組み立て、表へ追加する。
    for (Anime work : works) {
      // 日付・時刻・曜日は、すべて日本時間の初回放送日時から取り出す。
      var first = work.firstBroadcast();
      var startsAt = first == null || first.startsAt() == null ? null
        : first.startsAt().withZoneSameInstant(ZoneId.of("Asia/Tokyo"));

      // HTTP(S)の公式URLはリンクにし、それ以外は文字列、未登録はダッシュにする。
      var url = work.officialSiteUrl();
      String link = "—";
      if (url != null) {
        link = toMarkdownCell(url.toString());
        if ("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme())) {
          link = "[公式サイト](<" + url.toASCIIString().replace("|", "%7C") + ">)";
        }
      }

      // 局名やタイトルの記号をエスケープして、表の列や書式の崩れを防ぐ。
      markdown.add("| " + String.join(" | ",
        "[" + toMarkdownCell(work.title()) + "](https://annict.com/works/" + work.annictId() + ")",
        startsAt == null ? "N/A" : DATE.format(startsAt) + "（" + DAY.format(startsAt) + "） " + TIME.format(startsAt),
        toMarkdownCell(channelName(first)), link) + " |");
    }

    // 空の表だけでは検索結果が分かりにくいため、該当なしの案内を添える。
    if (works.isEmpty()) {
      markdown.add("").add("該当する作品はありません。");
    }

    // 日本語をUTF-8に変換してgzip圧縮し、URLへ格納するデータを小さくする。
    var compressed = new ByteArrayOutputStream();
    try (var gzip = new GZIPOutputStream(compressed)) {
      gzip.write(markdown.toString().getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("Could not compress Markdown", e);
    }

    // 圧縮したバイト列をBase64化し、クエリパラメータとして安全に渡せる形にする。
    String url = "https://kitware.github.io/markdown-viewer/?mdz="
      + URLEncoder.encode(Base64.getEncoder().encodeToString(compressed.toByteArray()), StandardCharsets.UTF_8);

    // LINEの1メッセージに収まるURLだけを返す。検索結果は切り捨てない。
    return url.length() <= 5000 ? url
      : "検索結果が多いため表示用URLを作成できません。タイトルや日時を指定して検索範囲を絞ってください。";
  }

  /** 外部の文字列を表のセル内の文字として表示し、Markdown構文として解釈させない。 */
  private String toMarkdownCell(String value)
  {
    // 改行・タブを除いた文字列を走査し、MarkdownのASCII記号をエスケープする。
    var escaped = new StringBuilder();
    for (char ch : toTsvCell(value).toCharArray()) {
      if ((ch >= '!' && ch <= '/') || (ch >= ':' && ch <= '@')
          || (ch >= '[' && ch <= '`') || (ch >= '{' && ch <= '~')) {
        escaped.append('\\');
      }
      escaped.append(ch);
    }

    return escaped.toString();
  }

  // ###########################################################################
  // MARK: TSVへの整形

  /** 作品を日本時間の初回放送日・時刻・曜日・局名・タイトル・公式URLの6列に変換する。 */
  private String toTsvRow(Anime work)
  {
    // 出力する日付・時刻・曜日の基準を日本時間に揃える。
    var first = work.firstBroadcast();
    var startsAt = first == null || first.startsAt() == null ? null
      : first.startsAt().withZoneSameInstant(ZoneId.of("Asia/Tokyo"));

    // 列順を固定し、公式URLが未登録でも末尾の空欄を残す。
    return String.join("\t",
      startsAt == null ? "N/A" : DATE.format(startsAt),
      startsAt == null ? "N/A" : TIME.format(startsAt),
      startsAt == null ? "N/A" : DAY.format(startsAt),
      toTsvCell(channelName(first)),
      toTsvCell(work.title()),
      work.officialSiteUrl() == null ? "" : toTsvCell(work.officialSiteUrl().toString())
    );
  }

  private String channelName(IAnnictClient.FirstBroadcast first) {
    return first == null || first.channelName() == null || first.channelName().isBlank()
      ? "N/A" : first.channelName();
  }

  /** タブと改行を空白へ置き換え、外部データによるTSVの列・行の崩れを防ぐ。 */
  private String toTsvCell(String value)
  {
    return value.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
  }
}
