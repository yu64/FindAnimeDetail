package app.usecase;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public sealed interface IInput {


  public static record HelpInput(String cmd) implements IInput {};


  /** from は初回放送日時の下限（nullなら現在日時）。statuses は出力する区分、plain は状態・検索語・理由の列を省く。 */
  public static record FindInput(Format fmt, List<String> words, OffsetDateTime from, Set<Status> statuses,
      boolean plain) implements IInput {

    public FindInput {
      words = List.copyOf(words);
      statuses = statuses.isEmpty() ? EnumSet.allOf(Status.class) : EnumSet.copyOf(statuses);
    }

    /** すべての区分を、すべての列で出力する。 */
    public FindInput(Format fmt, List<String> words, OffsetDateTime from) {
      this(fmt, words, from, Set.of(), false);
    }

    public FindInput(Format fmt, List<String> words) {
      this(fmt, words, null);
    }

    public static enum Format {
      TSV, MD
    }

    /** 検索語ごとの区分。label は出力する状態の名前。 */
    public static enum Status {
      HIT("放送予定"), SKIP("対象外"), MISS("未登録");

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
