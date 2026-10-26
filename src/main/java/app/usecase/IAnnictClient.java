package app.usecase;

import java.net.URI;
import java.time.ZonedDateTime;
import java.util.List;

/** 検索語・シーズンごとの作品検索と、優先局での初回放送の取得。期間・状態の判定と整形は呼び出し側で行う。 */
public interface IAnnictClient {

  /** 1検索語（またはシーズン）あたりの作品数と、1作品あたりの放送データ数の上限。Annictはリクエスト回数で制限されるため、続きは取得しない。 */
  public int PAGE_SIZE = 50;

  /**
   * 検索語ごとの作品。word は検索語で、シーズンだけで探した場合は null。
   * exists は、シーズンに関係なく検索語に一致する作品があるか。
   */
  public record WordResult(String word, List<Anime> works, boolean exists) {
    public WordResult {
      works = List.copyOf(works);
    }
  }

  /**
   * season は 2027-winter 形式で、未設定なら null。
   * firstBroadcast は優先局（候補がなければ局不明）の初回放送で、なければ null。
   * firstAiredAt は局を問わず再放送を除いた最初の放送日時で、なければ null。
   * hasBroadcasts は再放送を除く放送データが1件でもあるか。
   */
  public record Anime(int annictId, String title, URI officialSiteUrl, String season,
      FirstBroadcast firstBroadcast, ZonedDateTime firstAiredAt, boolean hasBroadcasts) {}

  /** startsAtはAsia/Tokyo。日時・局名は不明ならnull、局IDは不明なら0。 */
  public record FirstBroadcast(int channelId, String channelName, ZonedDateTime startsAt) {}

  /**
   * 検索語ごと（検索語がなければシーズン全体）に作品を検索し、入力順（重複・空白を除く）の結果を返す。
   * seasons を指定すると、Annict 側でそのシーズンの作品に絞り込む。問い合わせは1回だけ行う。
   * 優先順は AnnictConfig.channelPriority()。
   */
  public List<WordResult> search(List<String> words, List<String> seasons, List<Integer> channelPriority);
}
