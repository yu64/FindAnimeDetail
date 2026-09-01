package app.infrastructure;

import app.config.AnnictConfig;
import app.infrastructure.AnimeSearchResult.Broadcast;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Annict GraphQL APIを使うアニメ情報検索のプロトタイプ。
 * タイトルの一部、公式URL、放送局、各話の放送開始日時を一度の問い合わせで取得する。
 */
@ApplicationScoped
public class AnnictAnimeSearchClient {

  // ========== API設定 ==========

  /** APIへの接続タイムアウト */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

  /** APIからの応答タイムアウト */
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  /** 1回の検索で取得できる最大作品数 */
  private static final int MAX_SEARCH_RESULTS = 50;

  // ========== GraphQL ==========

  /** タイトルに一致する作品と放送予定を検索するクエリ */
  private static final String QUERY = """
    query SearchAnime($titles: [String!], $limit: Int) {
      searchWorks(titles: $titles, first: $limit) {
        edges {
          node {
            annictId
            title
            officialSiteUrl
            programs(first: 50, orderBy: { field: STARTED_AT, direction: ASC }) {
              edges {
                node {
                  startedAt
                  channel { name }
                  episode { numberText title }
                }
              }
            }
          }
        }
      }
    }
  """;

  // ========== 依存オブジェクト ==========

  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final URI endpoint;
  private final String accessToken;

  // ========== コンストラクタ ==========

  @Inject
  public AnnictAnimeSearchClient(
    ObjectMapper objectMapper,
    AnnictConfig config
  ) {
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder()
      .connectTimeout(CONNECT_TIMEOUT)
      .build();
    this.endpoint = config.url();
    this.accessToken = config.token();
  }

  // ========== アニメ情報検索 ==========

  /**
   * タイトルの一部に一致するアニメ情報を検索
   *
   * @param titleFragment 検索するタイトルの一部
   * @param limit 取得する作品数
   * @return 検索に一致したアニメ情報
   */
  public List<AnimeSearchResult> searchByTitle(String titleFragment, int limit) {
    validateSearchCondition(titleFragment, limit);

    try {
      HttpRequest request = createRequest(titleFragment, limit);
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      validateResponse(response);
      return mapResponse(objectMapper.readTree(response.body()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AnimeSearchException("Annict API request was interrupted", e);
    } catch (IOException e) {
      throw new AnimeSearchException("Could not call Annict API", e);
    }
  }

  // ========== リクエスト生成 ==========

  /** GraphQLリクエストを生成 */
  private HttpRequest createRequest(String titleFragment, int limit) throws IOException {
    String body = objectMapper.writeValueAsString(
      Map.of(
        "query", QUERY,
        "variables", Map.of(
          "titles", List.of(titleFragment.strip()),
          "limit", limit
        )
      )
    );

    return HttpRequest.newBuilder(endpoint)
      .timeout(REQUEST_TIMEOUT)
      .header("Authorization", "Bearer " + accessToken)
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .build();
  }

  // ========== レスポンス変換 ==========

  /** GraphQLレスポンスをアニメ検索結果へ変換 */
  private List<AnimeSearchResult> mapResponse(JsonNode root) {
    validateGraphQlResponse(root);

    List<AnimeSearchResult> results = new ArrayList<>();
    for (JsonNode edge : root.path("data").path("searchWorks").path("edges")) {
      results.add(mapWork(edge.path("node")));
    }
    return List.copyOf(results);
  }

  /** 作品情報を検索結果へ変換 */
  private AnimeSearchResult mapWork(JsonNode work) {
    String officialUrl = nullableText(work, "officialSiteUrl");

    return new AnimeSearchResult(
      work.path("annictId").asInt(),
      work.path("title").asText(),
      officialUrl == null ? null : URI.create(officialUrl),
      mapBroadcasts(work.path("programs").path("edges"))
    );
  }

  /** 放送予定を一覧へ変換 */
  private List<Broadcast> mapBroadcasts(JsonNode programEdges) {
    List<Broadcast> broadcasts = new ArrayList<>();
    for (JsonNode programEdge : programEdges) {
      JsonNode program = programEdge.path("node");
      JsonNode episode = program.path("episode");

      broadcasts.add(new Broadcast(
        program.path("channel").path("name").asText(),
        OffsetDateTime.parse(program.path("startedAt").asText()),
        nullableText(episode, "numberText"),
        nullableText(episode, "title")
      ));
    }
    return broadcasts;
  }

  // ========== 入出力検証 ==========

  /** 検索条件を検証 */
  private static void validateSearchCondition(String titleFragment, int limit) {
    if (titleFragment == null || titleFragment.isBlank()) {
      throw new IllegalArgumentException("titleFragment must not be blank");
    }
    if (limit < 1 || limit > MAX_SEARCH_RESULTS) {
      throw new IllegalArgumentException(
        "limit must be between 1 and " + MAX_SEARCH_RESULTS
      );
    }
  }

  /** HTTPレスポンスを検証 */
  private static void validateResponse(HttpResponse<String> response) {
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new AnimeSearchException(
        "Annict API returned HTTP " + response.statusCode()
      );
    }
  }

  /** GraphQLレスポンスを検証 */
  private static void validateGraphQlResponse(JsonNode root) {
    JsonNode errors = root.path("errors");
    if (errors.isArray() && !errors.isEmpty()) {
      throw new AnimeSearchException(
        "Annict GraphQL error: " + errors.path(0).path("message").asText()
      );
    }
  }

  /** 空文字とJSON nullをJavaのnullへ変換 */
  private static String nullableText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
  }
}
