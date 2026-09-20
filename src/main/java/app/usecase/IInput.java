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
      TSV,
      MD
    }
  };


}
