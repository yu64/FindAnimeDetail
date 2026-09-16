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
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.Base64;
import java.util.Collection;
import java.util.zip.GZIPOutputStream;

@ApplicationScoped 
public class FindUsecase {

  // ###########################################################################
  // MARK: 依存オブジェクト・出力形式

  private final IAnnictClient annict;
  private final AnnictConfig config;

  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd");
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("E", Locale.JAPANESE);

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
    // 検索前に全条件を組み立て、空タイトルなどの不正入力を検証する。
    var conditions = new LinkedHashSet<SearchCondition>();
    var from = input.from() == null ? OffsetDateTime.now(ZoneId.of("Asia/Tokyo")) : input.from();

    for (String word : input.words()) {
      conditions.add(new SearchCondition(word, List.of(), from, false));
    }

    // 入力タイトルの順、各検索結果の順を保ち、同じ作品は最初の結果を採用する。
    var works = new LinkedHashMap<Integer, Anime>();
    var priorities = config.channelPriority();

    for (SearchCondition condition : conditions) {
      for (Anime work : annict.search(condition, priorities)) {
        // 出力に必要な初回放送日時と局名が揃っている作品だけを採用する。
        var first = work.firstBroadcast();
        if (first == null || first.startsAt() == null
            || first.channelId() <= 0 || first.channelName() == null || first.channelName().isBlank()) {
          continue;
        }

        // 複数のタイトルに一致した作品も、最初に取得した情報で1件にまとめる。
        works.putIfAbsent(work.annictId(), work);
      }
    }

    // Markdown指定なら、表をブラウザで開くためのURLを返す。
    if (input.fmt() == FindInput.Format.MD) {
      return toMarkdownViewerUrl(works.values());
    }

    // TSVはヘッダーに続けて作品を並べる。0件の場合も列名は返す。
    var tsv = new StringJoiner("\n");
    tsv.add("放送開始日\t時刻\t曜日\t放送局\tタイトル\t公式URL");

    for (Anime work : works.values()) {
      tsv.add(toTsvRow(work));
    }

    return tsv.toString();
  }

  // ###########################################################################
  // MARK: Markdown Viewerへの整形

  /** Markdown表をUTF-8・gzip・Base64の順で変換し、Viewerのmdzパラメータに渡す。 */
  private String toMarkdownViewerUrl(Collection<Anime> works)
  {
    // TSVと同じ6列で、Markdown表のヘッダーと区切り行を用意する。
    var markdown = new StringJoiner("\n");
    markdown.add("| 放送開始日 | 時刻 | 曜日 | 放送局 | タイトル | 公式URL |");
    markdown.add("| --- | --- | --- | --- | --- | --- |");

    // 作品ごとに表示用の日時・リンクを組み立て、表へ追加する。
    for (Anime work : works) {
      // 日付・時刻・曜日は、すべて日本時間の初回放送日時から取り出す。
      var first = work.firstBroadcast();
      var startsAt = first.startsAt().withZoneSameInstant(ZoneId.of("Asia/Tokyo"));

      // HTTP(S)の公式URLはリンクにし、それ以外は文字列、未登録は空欄にする。
      var url = work.officialSiteUrl();
      String link = "";
      if (url != null) {
        link = toMarkdownCell(url.toString());
        if ("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme())) {
          link = "[公式サイト](<" + url.toASCIIString().replace("|", "%7C") + ">)";
        }
      }

      // 局名やタイトルの記号をエスケープして、表の列や書式の崩れを防ぐ。
      markdown.add("| " + String.join(" | ",
        DATE.format(startsAt), TIME.format(startsAt), DAY.format(startsAt),
        toMarkdownCell(first.channelName()), toMarkdownCell(work.title()), link) + " |");
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
    var startsAt = first.startsAt().withZoneSameInstant(ZoneId.of("Asia/Tokyo"));

    // 列順を固定し、公式URLが未登録でも末尾の空欄を残す。
    return String.join("\t",
      DATE.format(startsAt),
      TIME.format(startsAt),
      DAY.format(startsAt),
      toTsvCell(first.channelName()),
      toTsvCell(work.title()),
      work.officialSiteUrl() == null ? "" : toTsvCell(work.officialSiteUrl().toString())
    );
  }

  /** タブと改行を空白へ置き換え、外部データによるTSVの列・行の崩れを防ぐ。 */
  private String toTsvCell(String value)
  {
    return value.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
  }
}
