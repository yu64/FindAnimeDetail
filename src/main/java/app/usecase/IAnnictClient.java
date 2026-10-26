package app.usecase;

import java.net.URI;
import java.time.ZonedDateTime;
import java.util.List;

/** 検索語ごとの作品検索と、優先局での初回放送の取得。区分の判定と整形は呼び出し側で行う。 */
public interface IAnnictClient {

  /** 1検索語あたりの作品数と、1作品あたりの放送データ数の上限。Annictはリクエスト回数で制限されるため、続きは取得しない。 */
  public int PAGE_SIZE = 50;

  /** 検索語ごとの作品。works は Annict の返却順で、作品の重複はない。 */
  public record WordResult(String word, List<Anime> works) {
    public WordResult {
      works = List.copyOf(works);
    }
  }

  /**
   * firstBroadcast は優先局（候補がなければ局不明）の初回放送で、なければ null。
   * hasBroadcasts は再放送を除く放送データが1件でもあるか。
   */
  public record Anime(int annictId, String title, URI officialSiteUrl, FirstBroadcast firstBroadcast,
      boolean hasBroadcasts) {}

  /** startsAtはAsia/Tokyo。日時・局名は不明ならnull、局IDは不明なら0。 */
  public record FirstBroadcast(int channelId, String channelName, ZonedDateTime startsAt) {}

  /**
   * 検索語ごとに作品を検索し、入力順（重複・空白を除く）の結果を返す。問い合わせは1回だけ行う。
   * 優先順は AnnictConfig.channelPriority()。
   */
  public List<WordResult> search(List<String> words, List<Integer> channelPriority);
}
