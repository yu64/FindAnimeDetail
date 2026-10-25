package app.usecase;

import app.config.EdcbConfig;
import app.usecase.IInput.ReserveInput;
import app.usecase.IInput.ReserveInput.Entry;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/** 作品をEDCBのEPG自動予約に登録し、入力の行ごとの結果をTSVで返す。 */
@ApplicationScoped
public class ReserveUsecase {

  // ###########################################################################
  // MARK: 依存オブジェクト・出力形式

  private final IEdcbClient edcb;
  private final EdcbConfig config;

  private static final String HEADER = "結果\tタイトル\tキーワード\tEPGヒット\t備考";

  /** 期数・シーズンの表記。EPGの番組名では表記が揺れるため、キーワードから除いて録画漏れを防ぐ。 */
  private static final String SEASON = "(?:第\\s*[0-9０-９一二三四五六七八九十]+\\s*(?:期|クール|シーズン|部|章|幕)"
      + "|[0-9０-９]+\\s*期"
      + "|(?:season|シーズン)\\s*[0-9０-９]+"
      + "|[0-9０-９]+\\s*(?:st|nd|rd|th)\\s*season"
      + "|(?:the\\s*)?final\\s*season|ファイナルシーズン)";
  private static final Pattern TRAILING_SEASON = Pattern.compile(
      "[\\s　]*(?:[(（\\[［【]\\s*" + SEASON + "\\s*[)）\\]］】]|" + SEASON + ")$",
      Pattern.CASE_INSENSITIVE);

  /** Windows・Linuxのどちらでもフォルダ名に使えない文字と制御文字。 */
  private static final Pattern INVALID_FOLDER_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");

  /** EDCBの自動予約の登録と、保存先の親フォルダを含む設定を受け取る。 */
  @Inject
  public ReserveUsecase(IEdcbClient edcb, EdcbConfig config) {
    this.edcb = edcb;
    this.config = config;
  }

  // ###########################################################################
  // MARK: 自動予約の登録

  /** 既存の自動予約や同じ入力内と重複するキーワードは登録しない。 */
  public String run(ReserveInput input) {
    var tsv = new StringJoiner("\n");
    tsv.add(HEADER);

    // 既存の自動予約を取得できなければ、EDCBに接続できないため全行を失敗にする。
    Set<String> registered = new HashSet<>();
    try {
      this.edcb.autoAddKeywords().forEach(keyword -> registered.add(normalize(keyword)));
    } catch(EdcbException e) {
      Log.error("Could not list EDCB auto-adds", e);
      for(Entry entry : input.entries()) {
        tsv.add(row("失敗", entry.title(), entry.error() == null ? keyword(entry.title()) : "", "-",
            entry.error() == null ? "EDCBに接続できません" : entry.error()));
      }
      return tsv.toString();
    }

    // 通信に失敗した後は、タイムアウトを待ち続けないよう残りの行を中止する。
    boolean aborted = false;
    for(Entry entry : input.entries()) {
      if(entry.error() != null) {
        tsv.add(row("失敗", entry.title(), "", "-", entry.error()));
        continue;
      }

      String keyword = keyword(entry.title());
      if(aborted) {
        tsv.add(row("失敗", entry.title(), keyword, "-", "前の行でEDCBへの登録に失敗したため中止しました"));
        continue;
      }
      if(!registered.add(normalize(keyword))) {
        tsv.add(row("重複", entry.title(), keyword, "-", "同じキーワードの自動予約があります"));
        continue;
      }

      try {
        int hits = this.edcb.countEvents(keyword);
        this.edcb.addAutoAdd(keyword, this.recFolder(entry.title()));
        tsv.add(row("登録", entry.title(), keyword, String.valueOf(hits),
            hits == 0 ? "EPGに一致する番組はまだありません" : ""));
      } catch(EdcbException e) {
        Log.error("Could not add EDCB auto-add", e);
        tsv.add(row("失敗", entry.title(), keyword, "-", "EDCBへの登録に失敗しました"));
        aborted = true;
      }
    }

    return tsv.toString();
  }

  // ###########################################################################
  // MARK: キーワード・保存先の作成

  /** 末尾の期数・シーズン表記を繰り返し除く。すべて除くと空になる場合は元のタイトルを使う。 */
  static String keyword(String title) {
    String keyword = title.strip();
    while(true) {
      String stripped = TRAILING_SEASON.matcher(keyword).replaceFirst("").strip();
      if(stripped.isEmpty() || stripped.equals(keyword)) return keyword;
      keyword = stripped;
    }
  }

  /** 全角英数・空白の違いと大文字小文字を無視して、キーワードの重複を判定する。 */
  static String normalize(String keyword) {
    var normalized = new StringBuilder();
    for(char ch : keyword.toCharArray()) {
      // Native Imageの正規化データに依存せず、全角ASCIIを半角に揃える。
      if(ch >= '！' && ch <= '～') ch = (char) (ch - 0xFEE0);
      if(!Character.isWhitespace(ch) && ch != '　') normalized.append(Character.toLowerCase(ch));
    }
    return normalized.toString();
  }

  /** 作品名をフォルダ名に使えるようにし、親フォルダの下に置く。区切りはEDCBがLinuxでも使える '/' にする。 */
  private String recFolder(String title) {
    String folder = INVALID_FOLDER_CHARS.matcher(title.strip()).replaceAll("_");
    // Windowsでは末尾のドット・空白をフォルダ名に使えない。
    folder = folder.replaceAll("[.\\s]+$", "");
    if(folder.isEmpty()) folder = "_";
    return this.config.recFolder().replaceAll("[/\\\\]+$", "") + "/" + folder;
  }

  /** 列の順は HEADER と同じ。 */
  private static String row(String result, String title, String keyword, String hits, String note) {
    return String.join("\t", List.of(result, title, keyword, hits, note));
  }
}
