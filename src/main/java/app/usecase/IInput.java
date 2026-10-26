package app.usecase;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public sealed interface IInput {


  public static record HelpInput(String cmd) implements IInput {};


  /**
   * words は空なら期間だけで探す。period は null なら今季と来季（実行時に求める）。
   * statuses は出力する状態（空なら放送予定だけ）、plain は状態・検索語の列を省く。
   */
  public static record FindInput(Format fmt, List<String> words, Period period, Set<Status> statuses,
      boolean plain) implements IInput {

    public FindInput {
      words = List.copyOf(words);
      statuses = statuses.isEmpty() ? EnumSet.of(Status.HIT) : EnumSet.copyOf(statuses);
    }

    /** 放送予定だけを、すべての列で出力する。 */
    public FindInput(Format fmt, List<String> words, Period period) {
      this(fmt, words, period, Set.of(), false);
    }

    /** 期間を指定しない場合は、今季と来季を期間とする。 */
    public FindInput(Format fmt, List<String> words) {
      this(fmt, words, null);
    }

    public static enum Format {
      TSV, MD
    }

    /** 期間。シーズンか日時のどちらか一方で指定する。 */
    public sealed interface Period {

      /** 作品のシーズンがいずれかに一致すれば期間内。 */
      public record Seasons(List<Season> seasons) implements Period {
        public Seasons {
          seasons = List.copyOf(seasons);
        }
      }

      /** 初回放送が from 以上・to 未満なら期間内。null の側は制限なし。 */
      public record Range(OffsetDateTime from, OffsetDateTime to) implements Period {}
    }

    /** 作品または検索語の状態。label は出力する状態の名前。 */
    public static enum Status {
      HIT("放送予定"), DONE("放送済み"), SKIP("対象外"), OUT("期間外"), MISS("未登録");

      public final String label;

      Status(String label) {
        this.label = label;
      }
    }
  };


  /** entries は入力の行順。形式が不正な行は error を持ち、予約せずに失敗として返す。 */
  public static record ReserveInput(List<Entry> entries) implements IInput {

    public ReserveInput {
      entries = List.copyOf(entries);
    }

    /** title は予約する作品名。error が null でなければ、title は結果に表示する入力行の内容。 */
    public static record Entry(String title, String error) {

      public static Entry ok(String title) {
        return new Entry(title, null);
      }

      public static Entry err(String title, String error) {
        return new Entry(title, error);
      }
    }
  };


}
