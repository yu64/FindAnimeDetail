package app.usecase;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Annictのシーズン（例: 2027-winter）。日時との対応は今季・来季を求めるときだけに使う。 */
public record Season(int year, Name name) {

  public static enum Name {
    WINTER, SPRING, SUMMER, AUTUMN
  }

  private static final Pattern FORMAT = Pattern.compile("([0-9]{4})-(winter|spring|summer|autumn)");

  /** 1〜3月を冬、4〜6月を春、7〜9月を夏、10〜12月を秋とする慣例で、日付のシーズンを求める。 */
  public static Season of(LocalDate date) {
    return new Season(date.getYear(), Name.values()[(date.getMonthValue() - 1) / 3]);
  }

  /** 2027-winter 形式、now（今季）、next（来季）を解釈する。 */
  public static Optional<Season> parse(String text, LocalDate today) {
    var value = text.strip().toLowerCase(Locale.ROOT);
    if(value.equals("now")) return Optional.of(of(today));
    if(value.equals("next")) return Optional.of(of(today).next());

    var matcher = FORMAT.matcher(value);
    if(!matcher.matches()) return Optional.empty();
    return Optional.of(new Season(Integer.parseInt(matcher.group(1)),
        Name.valueOf(matcher.group(2).toUpperCase(Locale.ROOT))));
  }

  public Season next() {
    return this.name == Name.AUTUMN
        ? new Season(this.year + 1, Name.WINTER)
        : new Season(this.year, Name.values()[this.name.ordinal() + 1]);
  }

  public Season previous() {
    return this.name == Name.WINTER
        ? new Season(this.year - 1, Name.AUTUMN)
        : new Season(this.year, Name.values()[this.name.ordinal() - 1]);
  }

  public boolean isAfter(Season other) {
    return this.year != other.year ? this.year > other.year : this.name.ordinal() > other.name.ordinal();
  }

  /** Annictの seasons 引数と同じ 2027-winter 形式。 */
  @Override
  public String toString() {
    return this.year + "-" + this.name.name().toLowerCase(Locale.ROOT);
  }
}
