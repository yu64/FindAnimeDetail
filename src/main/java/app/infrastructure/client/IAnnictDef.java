package app.infrastructure.client;

import app.infrastructure.AnimeSearchException;
import app.usecase.IAnnictClient;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.core.MediaType;
import java.net.URI;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/** Annict GraphQLをJSONのPOSTとして呼び出すQuarkus REST Client定義。 */
@RegisterRestClient(configKey = "annict-api")
@ClientHeaderParam(name = "Authorization", value = "Bearer ${annict-api.token}")
public interface IAnnictDef extends IAnnictClient {

  // ###########################################################################
  // MARK: GraphQLクエリ

  /** 複数タイトルをOR検索し、作品と放送データの先頭ページをまとめて取得する。 */
  public String WORKS_QUERY = """
    query SearchWorks($titles: [String!], $seasons: [String!], $after: String) {
      searchWorks(titles: $titles, seasons: $seasons, first: 50, after: $after) {
        nodes {
          annictId
          title
          officialSiteUrl
          programs(first: 50, orderBy: {field: STARTED_AT, direction: ASC}) {
            nodes {
              startedAt
              channel { annictId name }
            }
            pageInfo { hasNextPage endCursor }
          }
        }
        pageInfo {
          hasNextPage
          endCursor
        }
      }
    }
    """;

  /** 初回を判定できるよう、過去分も含めて放送日時の昇順で取得する。 */
  public String PROGRAMS_QUERY = """
    query FirstBroadcasts($ids: [Int!], $after: String) {
      searchWorks(annictIds: $ids, first: 1) {
        nodes {
          programs(first: 50, after: $after, orderBy: {field: STARTED_AT, direction: ASC}) {
            nodes {
              startedAt
              channel {
                annictId
                name
              }
            }
            pageInfo {
              hasNextPage
              endCursor
            }
          }
        }
      }
    }
    """;

  // ###########################################################################
  // MARK: HTTP通信の定義

  /** Annictへ送信するGraphQLクエリ本文と変数を保持するリクエスト。 */
  public record GraphQlRequest(String query, Map<String, Object> variables) {}

  /** AnnictへGraphQLリクエストをPOSTし、JSONレスポンスを返す。 */
  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @jakarta.ws.rs.Produces(MediaType.APPLICATION_JSON)
  public JsonNode execute(GraphQlRequest request);

  // ###########################################################################
  // MARK: 作品検索・日時条件による絞り込み

  /** 現在日時を基準に条件に一致する全作品を検索し、優先局の初回放送情報とともに返す。 */
  @Override
  public default List<Anime> search(SearchCondition condition, List<Integer> channelPriority) {
    return search(condition, channelPriority, Clock.systemUTC());
  }

  /** 指定時計を基準に全作品を検索し、優先局の初回放送日時が条件を満たす作品を返す。 */
  public default List<Anime> search(SearchCondition condition, List<Integer> channelPriority, Clock clock) {

    // 呼び出し元のリスト変更の影響を避け、局IDの指定を検証する。
    Objects.requireNonNull(condition);
    List<Integer> priorities = List.copyOf(channelPriority);

    if (priorities.isEmpty() || priorities.stream().anyMatch(id -> id <= 0)) {
      throw new IllegalArgumentException("Specify positive channel IDs in priority order");
    }

    // ページ取得中に時刻が進んでも、すべての作品を同じ「現在」で判定する。
    var now = clock.instant();

    // 全ページの結果と、作品・カーソルの重複を検出する状態を保持する。
    List<Anime> results = new ArrayList<>();
    var workIds = new HashSet<Integer>();
    var cursors = new HashSet<String>();

    String after = null;
    do {

      // 指定された検索条件と、前ページから受け取ったカーソルを渡す。
      Map<String, Object> variables = new HashMap<>();

      if (!condition.titles().isEmpty()) {
        variables.put("titles", condition.titles());
      }
      if (!condition.seasons().isEmpty()) {
        variables.put("seasons", condition.seasons());
      }
      if (after != null) {
        variables.put("after", after);
      }

      // 作品一覧の1ページを取得し、各作品の初回放送を確認する。
      JsonNode works = request(WORKS_QUERY, variables).path("searchWorks");

      for (JsonNode work : nodes(works)) {

        // ページをまたいで同じ作品が返っても、出力は1作品につき1件にする。
        int id = work.path("annictId").asInt();

        if (id <= 0 || !work.path("title").isTextual()) {
          throw new AnimeSearchException("Invalid Annict work");
        }
        if (!workIds.add(id)) {
          continue;
        }

        // 先に優先局と初回を確定する。未来の放送を初回として拾い直さない。
        FirstBroadcast first = firstBroadcast(id, priorities, work.path("programs"));

        // 選定済みの初回放送が、指定された日時条件を満たすか判定する。
        if (first != null && first.startsAt() != null
            && (condition.minimumStartsAt() != null || condition.futureOnly())) {

          var start = first.startsAt().toInstant();

          // 最低日時は境界を含み、未来限定は検索開始時刻と同時の放送を含まない。
          if (condition.minimumStartsAt() != null
              && start.isBefore(condition.minimumStartsAt().toInstant())) {
            continue;
          }
          if (condition.futureOnly() && !start.isAfter(now)) {
            continue;
          }
        }

        // 条件を満たした作品を返却用の型へ変換する。未登録の公式URLはnullにする。
        String url = work.path("officialSiteUrl").asText("");

        results.add(new Anime(
          id,
          work.path("title").asText(),
          (url.isBlank() ? null : URI.create(url)),
          first
        ));
      }

      // 次ページがあれば継続し、末尾に到達したら結果を確定する。
      after = nextCursor(works, cursors);
    } while (after != null);

    return List.copyOf(results);
  }

  // ###########################################################################
  // MARK: 優先局と初回放送の選定

  /** 優先局の初回放送を選ぶ。欠損した項目はnullのまま保持する。 */
  private FirstBroadcast firstBroadcast(int workId, List<Integer> priorities, JsonNode programs) {
    Map<Integer, FirstBroadcast> firstByChannel = new HashMap<>();
    FirstBroadcast unknownChannel = null;
    var cursors = new HashSet<String>();

    while (true) {
      for (JsonNode program : nodes(programs)) {
        JsonNode channel = program.path("channel");
        int channelId = channel.path("annictId").asInt();
        // 判明している指定外の局は採用しない。局自体が不明なら補足候補として保持する。
        if (channelId > 0 && !priorities.contains(channelId)) continue;

        String name = channel.path("name").asText("");
        java.time.ZonedDateTime startsAt = null;
        try {
          startsAt = OffsetDateTime.parse(program.path("startedAt").asText(""))
            .atZoneSameInstant(ZoneId.of("Asia/Tokyo"));
        } catch (DateTimeParseException ignored) {
          // 欠損・解釈できない日時は不明のまま残す。
        }
        var candidate = new FirstBroadcast(Math.max(0, channelId), name.isBlank() ? null : name, startsAt);
        if (channelId <= 0) {
          if (unknownChannel == null || unknownChannel.startsAt() == null) unknownChannel = candidate;
          continue;
        }

        var previous = firstByChannel.get(channelId);
        if (previous == null || previous.startsAt() == null) {
          firstByChannel.put(channelId, candidate);
        }
        // 日時昇順の最優先局が確定した場合は、続きの取得を省略できる。
        if (channelId == priorities.getFirst() && startsAt != null) return candidate;
      }

      String after = nextCursor(programs, cursors);
      if (after == null) break;
      JsonNode works = nodes(request(PROGRAMS_QUERY,
        Map.of("ids", List.of(workId), "after", after)).path("searchWorks"));
      if (works.size() != 1) throw new AnimeSearchException("Annict work disappeared during search");
      programs = works.get(0).path("programs");
    }

    // 局の優先度は日時の有無より優先する。指定局がなければ局不明の情報を使う。
    for (int channelId : priorities) {
      if (firstByChannel.containsKey(channelId)) return firstByChannel.get(channelId);
    }
    return unknownChannel;
  }

  // ###########################################################################
  // MARK: 通信エラー・GraphQLレスポンスの検証

  /** GraphQLクエリを送信して応答を検証し、正常ならdataを返し、失敗なら検索例外を送出する。 */
  private JsonNode request(String query, Map<String, Object> variables) {

    // 通信時の例外を検索例外に揃え、原因を保持して呼び出し側へ伝える。
    JsonNode root;

    try {
      root = execute(new GraphQlRequest(query, variables));
    } catch (RuntimeException e) {
      throw new AnimeSearchException("Could not call Annict API", e);
    }

    // GraphQLではHTTPが成功していてもerrorsが返る場合がある。
    if (root == null || root.path("errors").size() > 0 || !root.path("data").isObject()) {
      throw new AnimeSearchException("Annict GraphQL response contains errors or missing data");
    }

    return root.get("data");
  }

  /** 接続情報からnodes配列を取得し、欠落または配列以外なら検索例外を送出する。 */
  private static JsonNode nodes(JsonNode connection) {
    if (!connection.path("nodes").isArray()) {
      throw new AnimeSearchException("Annict response is missing nodes");
    }

    return connection.get("nodes");
  }

  // ###########################################################################
  // MARK: ページングの制御

  /** 次ページのカーソルを返す。末尾ならnull、不正な応答なら例外にする。 */
  private static String nextCursor(JsonNode connection, HashSet<String> seen) {

    // ページ情報が欠落している場合は、末尾とみなさず異常な応答として扱う。
    JsonNode info = connection.path("pageInfo");

    if (!info.path("hasNextPage").isBoolean()) {
      throw new AnimeSearchException("Annict response is missing pageInfo");
    }

    // 正常に末尾へ到達した場合だけ、カーソルなしで取得を終了する。
    if (!info.get("hasNextPage").asBoolean()) {
      return null;
    }

    // カーソルの欠落・再出現を検出し、同じページを取得し続けることを防ぐ。
    String cursor = info.path("endCursor").asText("");

    if (cursor.isBlank() || !seen.add(cursor)) {
      throw new AnimeSearchException("Annict returned an invalid pagination cursor");
    }

    return cursor;
  }

  // ###########################################################################
  // MARK: ユースケース向けインターフェイスへのDI中継

  /** 生成されたREST Clientを、IAnnictClientとして注入できるようにする。 */
  @ApplicationScoped
  public class DiRelay {

    /** 生成されたAnnict REST Clientをユースケース向けのIAnnictClientとして返す。 */
    @Produces
    @ApplicationScoped
    public IAnnictClient relay(@RestClient IAnnictDef client) {
      return client;
    }
  }
}
