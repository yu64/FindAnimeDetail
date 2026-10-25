package app.presentation.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.quarkiverse.wiremock.devservice.ConnectWireMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** モックテスト: LINE の Webhook 受信から返信までを、LINE・Annict を WireMock に置き換えて確認する。 */
@QuarkusTest
@ConnectWireMock
class LineWebhookMockTest {

  /** application.properties の %test.line-bot.channel-secret と同じ値。 */
  private static final String CHANNEL_SECRET = "test-channel-secret";

  private static final String REPLY_PATH = "/v2/bot/message/reply";
  private static final String ANNICT_PATH = "/graphql";
  private static final String REPLY_TOKEN = "test-reply-token";
  private static final ObjectMapper JSON = new ObjectMapper();

  /** 優先局（TOKYO MX）で 2026-10-02(金) 23:00 JST に初回放送する作品。 */
  private static final String SHIROBAKO = """
      {"annictId":1,"title":"SHIROBAKO","officialSiteUrl":"https://example.com/shirobako",
       "programs":{"nodes":[{"startedAt":"2026-10-02T14:00:00Z","channel":{"annictId":19,"name":"TOKYO MX"}}],
                   "pageInfo":{"hasNextPage":false,"endCursor":null}}}
      """;

  /** 放送情報が登録されていない作品。 */
  private static final String NO_BROADCAST = """
      {"annictId":2,"title":"放送未定アニメ","officialSiteUrl":null,
       "programs":{"nodes":[],"pageInfo":{"hasNextPage":false,"endCursor":null}}}
      """;

  WireMock wiremock;

  @BeforeEach
  void reset() {
    this.wiremock.resetRequests();
    this.wiremock.resetMappings();
    this.wiremock.register(post(urlEqualTo(REPLY_PATH)).willReturn(ok()));
  }

  // ###########################################################################
  // MARK: 正常系

  @Test
  void findRepliesWithTsvThroughAnnict() throws Exception {
    annictReturnsWorks(SHIROBAKO);

    sendText("/find #word SHIROBAKO #from 2026-10");

    // 返信は LINE の返信 API に、正しい認証・返信トークンで届く。
    var reply = onlyReply();
    assertEquals("Bearer test-channel-token", reply.authorization());
    assertEquals(REPLY_TOKEN, reply.token());
    assertEquals(String.join("\n",
        "放送開始日\t時刻\t曜日\t放送局\tタイトル\t公式URL",
        "2026-10-02\t23:00\t金\tTOKYO MX\tSHIROBAKO\thttps://example.com/shirobako"), reply.text());

    // Annict には設定したトークンと検索語が送られる。
    var annict = this.wiremock.find(postRequestedFor(urlEqualTo(ANNICT_PATH)));
    assertEquals(1, annict.size());
    assertEquals("Bearer test-annict-token", annict.getFirst().getHeader("Authorization"));
    assertEquals(List.of("SHIROBAKO"), JSON.convertValue(
        JSON.readTree(annict.getFirst().getBodyAsString()).path("variables").path("titles"), List.class));
  }

  @Test
  void findWithMarkdownFormatRepliesWithViewerUrl() throws Exception {
    annictReturnsWorks(SHIROBAKO);

    sendText("/find #format md #word SHIROBAKO #from 2026-10");

    var text = onlyReply().text();
    var prefix = "https://kitware.github.io/markdown-viewer/?mdz=";
    assertTrue(text.startsWith(prefix), text);

    // URL に埋め込まれた Markdown を復元し、検索結果の表になっていることを確認する。
    var markdown = decodeMarkdown(text.substring(prefix.length()));
    assertTrue(markdown.contains("| [SHIROBAKO](https://annict.com/works/1) |"), markdown);
    assertTrue(markdown.contains("2026-10-02（金） 23:00"), markdown);
  }

  @Test
  void findHidesWorksWithUnknownBroadcastUnlessAllIsGiven() throws Exception {
    annictReturnsWorks(SHIROBAKO, NO_BROADCAST);

    sendText("/find #word SHIROBAKO #from 2026-10");
    assertFalse(onlyReply().text().contains("放送未定アニメ"));

    this.wiremock.resetRequests();
    sendText("/find #word SHIROBAKO #from 2026-10 #all");
    assertTrue(onlyReply().text().endsWith("N/A\tN/A\tN/A\tN/A\t放送未定アニメ\t"));
  }

  @Test
  void reserveAcceptsPastedFindTsvAndRepliesWithResultTsv() throws Exception {
    sendText(String.join("\n", "/reserve",
        "放送開始日\t時刻\t曜日\t放送局\tタイトル\t公式URL",
        "2026-10-02\t23:00\t金\tTOKYO MX\tSHIROBAKO\thttps://example.com/shirobako",
        "2026-10-03\t01:30\t土"));

    assertEquals(String.join("\n",
        "結果\tタイトル\tキーワード\tEPGヒット\t備考",
        "受付\tSHIROBAKO\tSHIROBAKO\t-\t予約処理は準備中です",
        "失敗\t2026-10-03 01:30 土\t\t-\tタイトルがありません"), onlyReply().text());
  }

  @Test
  void helpRepliesWithUsageOfAllCommands() throws Exception {
    sendText("/help");

    var text = onlyReply().text();
    assertTrue(text.contains("[/find]"), text);
    assertTrue(text.contains("[/help]"), text);
    assertTrue(text.contains("[/reserve]"), text);
  }

  // ###########################################################################
  // MARK: 入力エラー

  @Test
  void findWithoutWordRepliesWithParseError() throws Exception {
    sendText("/find #from 2026-10");

    var text = onlyReply().text();
    assertTrue(text.startsWith("コマンドのパースに失敗しました。"), text);
    assertTrue(text.contains("word は必須です"), text);
  }

  @Test
  void unauthorizedUserIsRejectedWithoutCallingAnnict() throws Exception {
    annictReturnsWorks(SHIROBAKO);

    sendWebhook(messageEvent(Map.of("type", "text", "id", "1", "text", "/find #word SHIROBAKO"), "other-user"))
        .then().statusCode(200);

    assertEquals("このボットを利用する権限がありません。", onlyReply().text());
    assertEquals(0, this.wiremock.find(postRequestedFor(urlEqualTo(ANNICT_PATH))).size());
  }

  @Test
  void unknownCommandRepliesWithParseError() throws Exception {
    sendText("/unknown");

    assertTrue(onlyReply().text().startsWith("コマンドのパースに失敗しました。"));
  }

  // ###########################################################################
  // MARK: 外部APIの異常

  @Test
  void annictRateLimitRepliesWithRetryNotice() throws Exception {
    this.wiremock.register(post(urlEqualTo(ANNICT_PATH)).willReturn(aResponse().withStatus(429)));

    sendText("/find #word SHIROBAKO #from 2026-10");

    assertEquals("Annictのアクセス制限により検索できませんでした。しばらく時間を置いてから再検索してください。",
        onlyReply().text());
  }

  @Test
  void annictFailuresReplyWithGenericMessageWithoutDetails() throws Exception {
    var failures = List.of(
        aResponse().withStatus(500).withBody("secret-detail"),
        aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER),
        okJson("{\"errors\":[{\"message\":\"secret-detail\"}]}"));

    for(var failure : failures) {
      reset();
      this.wiremock.register(post(urlEqualTo(ANNICT_PATH)).willReturn(failure));

      sendText("/find #word SHIROBAKO #from 2026-10");

      assertEquals("アニメ情報の取得に失敗しました。しばらく時間を置いてから再検索してください。", onlyReply().text());
    }
  }

  // ###########################################################################
  // MARK: 認証・LINEの疎通確認・対象外のイベント

  @Test
  void rejectsRequestWithoutSignature() {
    given().contentType("application/json")
        .body("{\"destination\":\"test\",\"events\":[]}")
        .post("/line/webhook")
        .then().statusCode(401);
  }

  @Test
  void rejectsRequestWithWrongSignature() throws Exception {
    given().contentType("application/json")
        .header("x-line-signature", Base64.getEncoder().encodeToString(new byte[32]))
        .body(messageEvent(Map.of("type", "text", "id", "1", "text", "/help")))
        .post("/line/webhook")
        .then().statusCode(401);

    assertEquals(0, replies().size());
  }

  @Test
  void acceptsConnectivityCheckWithoutReplying() throws Exception {
    sendWebhook("{\"destination\":\"test\",\"events\":[]}").then().statusCode(200);

    assertEquals(0, replies().size());
  }

  @Test
  void ignoresNonTextEvents() throws Exception {
    var sticker = messageEvent(Map.of(
        "type", "sticker", "id", "1", "packageId", "1", "stickerId", "1", "stickerResourceType", "STATIC"));
    var follow = webhook(Map.of(
        "type", "follow", "replyToken", REPLY_TOKEN, "timestamp", 0, "mode", "active",
        "webhookEventId", "test-event", "deliveryContext", Map.of("isRedelivery", false),
        "source", Map.of("type", "user", "userId", "test-user")));

    for(var body : List.of(sticker, follow)) {
      sendWebhook(body).then().statusCode(200);
    }

    assertEquals(0, replies().size());
  }

  // ###########################################################################
  // MARK: ヘルパー: Webhook の送信

  /** テキストメッセージを送り、200 が返ることを確認する。 */
  private static void sendText(String text) throws Exception {
    sendWebhook(messageEvent(Map.of("type", "text", "id", "1", "text", text))).then().statusCode(200);
  }

  /** LINE プラットフォームと同じ方法で署名し、Webhook を送る。 */
  private static Response sendWebhook(String json) throws Exception {
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    return given().contentType("application/json")
        .header("x-line-signature", sign(body))
        .body(body)
        .post("/line/webhook");
  }

  /** 本文を channel secret で HMAC-SHA256 し、Base64 にする。 */
  private static String sign(byte[] body) throws Exception {
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(CHANNEL_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return Base64.getEncoder().encodeToString(mac.doFinal(body));
  }

  /** ユーザーからのメッセージイベントを1件含む Webhook 本文を作る。 */
  private static String messageEvent(Map<String, Object> message) throws Exception {
    return messageEvent(message, "test-user");
  }

  /** userId は application.properties の %test.line-bot.allowed-user-ids にあれば許可される。 */
  private static String messageEvent(Map<String, Object> message, String userId) throws Exception {
    return webhook(Map.of(
        "type", "message", "replyToken", REPLY_TOKEN, "timestamp", 0, "mode", "active",
        "webhookEventId", "test-event", "deliveryContext", Map.of("isRedelivery", false),
        "source", Map.of("type", "user", "userId", userId),
        "message", message));
  }

  private static String webhook(Map<String, Object> event) throws Exception {
    return JSON.writeValueAsString(Map.of("destination", "test", "events", List.of(event)));
  }

  // ###########################################################################
  // MARK: ヘルパー: Annict のスタブ・LINE への返信の取得

  /** Annict の作品検索が、指定した作品を1ページで返すようにする。 */
  private void annictReturnsWorks(String... works) {
    this.wiremock.register(post(urlEqualTo(ANNICT_PATH)).willReturn(okJson("""
        {"data":{"searchWorks":{"nodes":[%s],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}
        """.formatted(String.join(",", works)))));
  }

  /** LINE の返信 API が受け取った返信の内容。 */
  private record Reply(String authorization, String token, String text) {}

  private List<LoggedRequest> replies() {
    return this.wiremock.find(postRequestedFor(urlEqualTo(REPLY_PATH)));
  }

  /** 返信がちょうど1件あることを確認し、その内容を返す。 */
  private Reply onlyReply() throws Exception {
    var requests = replies();
    assertEquals(1, requests.size());

    JsonNode body = JSON.readTree(requests.getFirst().getBodyAsString());
    assertEquals(1, body.path("messages").size());
    assertEquals("text", body.path("messages").get(0).path("type").asText());
    return new Reply(
        requests.getFirst().getHeader("Authorization"),
        body.path("replyToken").asText(),
        body.path("messages").get(0).path("text").asText());
  }

  /** Viewer の mdz パラメータ（URLエンコード・Base64・gzip）を Markdown に戻す。 */
  private static String decodeMarkdown(String mdz) throws Exception {
    byte[] gzip = Base64.getDecoder().decode(URLDecoder.decode(mdz, StandardCharsets.UTF_8));
    try (var in = new GZIPInputStream(new ByteArrayInputStream(gzip))) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
