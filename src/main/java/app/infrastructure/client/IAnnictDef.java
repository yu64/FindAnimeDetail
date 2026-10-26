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
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/** Annict GraphQLをJSONのPOSTとして呼び出すQuarkus REST Client定義。 */
@RegisterRestClient(configKey = "annict-api")
@ClientHeaderParam(name = "Authorization", value = "Bearer ${annict-api.token}")
public interface IAnnictDef extends IAnnictClient {

  // ###########################################################################
  // MARK: GraphQLクエリ

  /** 作品と、初回放送の判定に使う放送データ。放送データは局で絞り込めないため、古い順に取得して判定する。 */
  public String WORK_FIELDS = """
      nodes {
        annictId
        title
        officialSiteUrl
        programs(first: %d, orderBy: {field: STARTED_AT, direction: ASC}) {
          nodes {
            startedAt
            rebroadcast
            channel { annictId name }
          }
        }
      }
    """.formatted(PAGE_SIZE);

  /** 検索語ごとにエイリアス（w0, w1…）を付け、1回のリクエストにまとめる。 */
  public static String worksQuery(int count) {
    var params = new StringJoiner(", ");
    var fields = new StringJoiner("\n");
    for(int i = 0; i < count; i++) {
      params.add("$t%d: [String!]".formatted(i));
      fields.add("  w%d: searchWorks(titles: $t%d, first: %d) {\n%s  }".formatted(i, i, PAGE_SIZE, WORK_FIELDS));
    }
    return "query SearchWorks(" + params + ") {\n" + fields + "\n}";
  }

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
  // MARK: 作品検索

  @Override
  public default List<WordResult> search(List<String> words, List<Integer> channelPriority) {

    // 検索語の前後の空白と重複を除き、局IDの指定を検証する。
    List<String> titles = words.stream().map(String::strip).filter(word -> !word.isEmpty()).distinct().toList();
    List<Integer> priorities = List.copyOf(channelPriority);

    if(titles.isEmpty()) {
      throw new IllegalArgumentException("Specify at least one title");
    }
    if(priorities.isEmpty() || priorities.stream().anyMatch(id -> id <= 0)) {
      throw new IllegalArgumentException("Specify positive channel IDs in priority order");
    }

    // 検索語ごとの変数を渡し、全検索語を1回で問い合わせる。
    Map<String, Object> variables = new LinkedHashMap<>();
    for(int i = 0; i < titles.size(); i++) {
      variables.put("t" + i, List.of(titles.get(i)));
    }
    JsonNode data = request(worksQuery(titles.size()), variables);

    // エイリアスの番号から検索語を対応付ける。同じ検索語の中では同じ作品を1件にまとめる。
    List<WordResult> results = new ArrayList<>();
    for(int i = 0; i < titles.size(); i++) {
      List<Anime> works = new ArrayList<>();
      var workIds = new HashSet<Integer>();

      for(JsonNode work : nodes(data.path("w" + i))) {
        int id = work.path("annictId").asInt();
        if(id <= 0 || !work.path("title").isTextual()) {
          throw new AnimeSearchException("Invalid Annict work");
        }
        if(workIds.add(id)) {
          works.add(anime(id, work, priorities));
        }
      }
      results.add(new WordResult(titles.get(i), works));
    }

    return List.copyOf(results);
  }

  // ###########################################################################
  // MARK: 優先局と初回放送の選定

  /** 再放送を除いた放送データから、優先局の初回放送を選ぶ。欠損した項目はnullのまま保持する。 */
  private Anime anime(int id, JsonNode work, List<Integer> priorities) {
    Map<Integer, FirstBroadcast> firstByChannel = new HashMap<>();
    FirstBroadcast unknownChannel = null;
    boolean hasBroadcasts = false;

    for(JsonNode program : nodes(work.path("programs"))) {
      // 再放送は初回放送ではないため、判定に使わない。
      if(program.path("rebroadcast").asBoolean(false)) continue;
      hasBroadcasts = true;

      JsonNode channel = program.path("channel");
      int channelId = channel.path("annictId").asInt();
      // 判明している指定外の局は採用しない。局自体が不明なら補足候補として保持する。
      if(channelId > 0 && !priorities.contains(channelId)) continue;

      String name = channel.path("name").asText("");
      ZonedDateTime startsAt = null;
      try {
        startsAt = OffsetDateTime.parse(program.path("startedAt").asText(""))
            .atZoneSameInstant(ZoneId.of("Asia/Tokyo"));
      } catch(DateTimeParseException ignored) {
        // 欠損・解釈できない日時は不明のまま残す。
      }
      var candidate = new FirstBroadcast(Math.max(0, channelId), name.isBlank() ? null : name, startsAt);

      // 放送データは古い順のため、局ごとに最初の日時付きの放送を採用する。
      if(channelId <= 0) {
        if(unknownChannel == null || unknownChannel.startsAt() == null) unknownChannel = candidate;
        continue;
      }
      var previous = firstByChannel.get(channelId);
      if(previous == null || previous.startsAt() == null) {
        firstByChannel.put(channelId, candidate);
      }
    }

    // 局の優先度は日時の有無より優先する。指定局がなければ局不明の情報を使う。
    FirstBroadcast first = unknownChannel;
    for(int channelId : priorities) {
      if(firstByChannel.containsKey(channelId)) {
        first = firstByChannel.get(channelId);
        break;
      }
    }

    String url = work.path("officialSiteUrl").asText("");
    return new Anime(id, work.path("title").asText(), url.isBlank() ? null : URI.create(url), first,
        hasBroadcasts);
  }

  // ###########################################################################
  // MARK: 通信エラー・GraphQLレスポンスの検証

  /** GraphQLクエリを送信して応答を検証し、正常ならdataを返し、失敗なら検索例外を送出する。 */
  private JsonNode request(String query, Map<String, Object> variables) {

    // 通信時の例外を検索例外に揃え、原因を保持して呼び出し側へ伝える。
    JsonNode root;

    try {
      root = execute(new GraphQlRequest(query, variables));
    } catch(RuntimeException e) {
      throw new AnimeSearchException("Could not call Annict API", e);
    }

    // GraphQLではHTTPが成功していてもerrorsが返る場合がある。
    if(root == null || root.path("errors").size() > 0 || !root.path("data").isObject()) {
      throw new AnimeSearchException("Annict GraphQL response contains errors or missing data");
    }

    return root.get("data");
  }

  /** 接続情報からnodes配列を取得し、欠落または配列以外なら検索例外を送出する。 */
  private static JsonNode nodes(JsonNode connection) {
    if(!connection.path("nodes").isArray()) {
      throw new AnimeSearchException("Annict response is missing nodes");
    }

    return connection.get("nodes");
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
