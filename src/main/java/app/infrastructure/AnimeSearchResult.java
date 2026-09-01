package app.infrastructure;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;

/** Annictから取得した、アプリケーション側で扱うアニメ検索結果。 */
public record AnimeSearchResult(
  int annictId,
  String title,
  URI officialSiteUrl,
  List<Broadcast> broadcasts
) {

  public AnimeSearchResult {
    broadcasts = List.copyOf(broadcasts);
  }

  /** 1話分の放送予定。同じ局でも話数ごとに複数件返る。 */
  public record Broadcast(
    String station,
    OffsetDateTime startsAt,
    String episodeNumber,
    String episodeTitle
  ) {}
}
