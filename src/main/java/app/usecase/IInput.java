package app.usecase;

import java.util.List;
import java.time.OffsetDateTime;

public sealed interface IInput {


  public static record HelpInput(String cmd) implements IInput {};


  /** from は初回放送日時の下限。all は日時や局名が不明な作品も出力する。 */
  public static record FindInput(Format fmt, List<String> words, OffsetDateTime from, boolean all) implements IInput {

    public FindInput(Format fmt, List<String> words, OffsetDateTime from) {
      this(fmt, words, from, false);
    }

    public FindInput(Format fmt, List<String> words) {
      this(fmt, words, null, false);
    }

    public static enum Format {
      TSV, MD
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
