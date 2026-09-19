package app.usecase;

import java.util.List;
import java.time.OffsetDateTime;

public sealed interface IInput {


  public static record HelpInput(String cmd) implements IInput {};


  /** from は初回放送日時の下限。complete は日時と局名が揃った作品だけを出力する。 */
  public static record FindInput(Format fmt, List<String> words, OffsetDateTime from, boolean complete) implements IInput {

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
