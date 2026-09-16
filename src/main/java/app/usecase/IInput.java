package app.usecase;

import java.util.List;
import java.time.OffsetDateTime;

public sealed interface IInput {


  public static record HelpInput(String cmd) implements IInput {};


  /** from は初回放送日時の下限。null は検索実行時の現在日時。 */
  public static record FindInput(Format fmt, List<String> words, OffsetDateTime from) implements IInput {

    public FindInput(Format fmt, List<String> words) {
      this(fmt, words, null);
    }

    public static enum Format {
      TSV,
      MD
    }
  };


}
