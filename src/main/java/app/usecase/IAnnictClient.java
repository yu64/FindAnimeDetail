package app.usecase;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;

/** 作品検索と、優先局での初回放送の取得。TSVへの整形は呼び出し側で行う。 */
public interface IAnnictClient {

    /** minimumStartsAtは以上、futureOnlyは検索開始時刻より後。両方指定した場合は両条件を満たす。 */
    public record SearchCondition(
        String title,
        List<String> seasons,
        OffsetDateTime minimumStartsAt,
        boolean futureOnly
    ) {
        /** タイトルとクール一覧を正規化し、検索条件の指定とクールの書式を検証する。 */
        public SearchCondition {
            // 未指定を空値に揃え、呼び出し元によるクール一覧の変更を防ぐ。
            title = (title == null ? "" : title.strip());
            seasons = (seasons == null ? List.of() : List.copyOf(seasons));

            // タイトルかクールの少なくとも一方を、検索の手がかりとして必須にする。
            if (title.isEmpty() && seasons.isEmpty()) {
                throw new IllegalArgumentException("Specify title or seasons");
            }

            // APIへ渡すクールを「年-季節」の形式に限定する。
            for (String season : seasons) {
                if (!season.matches("[0-9]{4}-(winter|spring|summer|autumn)")) {
                    throw new IllegalArgumentException("Invalid season: " + season);
                }
            }
        }
    }

    /** 検索結果には、指定局の局名と初回放送日時が判明している作品のみを含む。 */
    public record Anime(int annictId, String title, URI officialSiteUrl, FirstBroadcast firstBroadcast) {}

    /** startsAtはAsia/Tokyo。日付・時刻・曜日はいずれもこの初回日時から取り出す。 */
    public record FirstBroadcast(int channelId, String channelName, ZonedDateTime startsAt) {}

    /** 条件に一致する全作品と指定局の優先順で選んだ初回放送情報を返す（優先順はAnnictConfig.channelPriority()）。 */
    public List<Anime> search(SearchCondition condition, List<Integer> channelPriority);
}
