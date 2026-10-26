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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** モックテスト: LINE の Webhook 受信から返信までを、LINE・Annict・EDCB を WireMock に置き換えて確認する。 */
@QuarkusTest
@ConnectWireMock
class LineWebhookMockTest {

  /** application.properties の %test.line-bot.channel-secret と同じ値。 */
  private static final String CHANNEL_SECRET = "test-channel-secret";

  private static final String REPLY_PATH = "/v2/bot/message/reply";
  private static final String ANNICT_PATH = "/graphql";
  private static final String EDCB_PATH = "/edcb/api/";
  private static final String REPLY_TOKEN = "test-reply-token";
  private static final ObjectMapper JSON = new ObjectMapper();

  /** 優先局（TOKYO MX）で 2026-10-02(金) 23:00 JST に初回放送する作品。 */
  private static final String SHIROBAKO = """
      {"annictId":1,"title":"SHIROBAKO","officialSiteUrl":"https://example.com/shirobako",
       "programs":{"nodes":[{"startedAt":"2026-10-02T14:00:00Z","rebroadcast":false,
                             "channel":{"annictId":19,"name":"TOKYO MX"}}]}}
      """;

  /** 放送情報が登録されていない作品。 */
  private static final String NO_BROADCAST = """
      {"annictId":2,"title":"放送未定アニメ","officialSiteUrl":null,"programs":{"nodes":[]}}
      """;

  /** 2027年冬の作品で、放送日時がまだ登録されていない。 */
  private static final String NEXT_SEASON = """
      {"annictId":3,"title":"来季アニメ","officialSiteUrl":null,"seasonYear":2027,"seasonName":"WINTER",
       "programs":{"nodes":[]}}
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
  void findRepliesWithTsvPerWordThroughOneAnnictRequest() throws Exception {
    annictReturnsWorks(SHIROBAKO + "," + NO_BROADCAST, "");

    sendText("/find #word SHIROBAKO, 存在しない作品 #from 2026-10 #hit #done #miss");

    // 返信は LINE の返信 API に、正しい認証・返信トークンで届く。
    var reply = onlyReply();
    assertEquals("Bearer test-channel-token", reply.authorization());
    assertEquals(REPLY_TOKEN, reply.token());
    assertEquals(String.join("\n",
        "状態\t検索語\t放送開始日\t時刻\t曜日\t放送局\tタイトル\t公式URL",
        "放送済み\tSHIROBAKO\t2026-10-02\t23:00\t金\tTOKYO MX\tSHIROBAKO\thttps://example.com/shirobako",
        "未登録\t存在しない作品\t\t\t\t\t存在しない作品\t"), reply.text());

    // Annict には1回だけ、設定したトークンと検索語ごとの変数が送られる。
    var annict = this.wiremock.find(postRequestedFor(urlEqualTo(ANNICT_PATH)));
    assertEquals(1, annict.size());
    assertEquals("Bearer test-annict-token", annict.getFirst().getHeader("Authorization"));
    var variables = JSON.readTree(annict.getFirst().getBodyAsString()).path("variables");
    assertEquals(List.of("SHIROBAKO"), JSON.convertValue(variables.path("t0"), List.class));
    assertEquals(List.of("存在しない作品"), JSON.convertValue(variables.path("t1"), List.class));
  }

  @Test
  void findWithMarkdownFormatRepliesWithViewerUrl() throws Exception {
    annictReturnsWorks(SHIROBAKO);

    sendText("/find #format md #word SHIROBAKO #from 2026-10 #done");

    var text = onlyReply().text();
    var prefix = "https://kitware.github.io/markdown-viewer/?mdz=";
    assertTrue(text.startsWith(prefix), text);

    // URL に埋め込まれた Markdown を復元し、検索結果の表になっていることを確認する。
    var markdown = decodeMarkdown(text.substring(prefix.length()));
    assertTrue(markdown.contains("| SHIROBAKO | [SHIROBAKO](https://annict.com/works/1) |"), markdown);
    assertTrue(markdown.contains("2026-10-02（金） 23:00"), markdown);
  }

  @Test
  void findSwitchesSelectStatusesAndPlainDropsColumns() throws Exception {
    annictReturnsWorks(SHIROBAKO + "," + NO_BROADCAST);

    sendText("/find #word SHIROBAKO #from 2026-10 #done #plain");

    assertEquals(String.join("\n",
        "放送開始日\t時刻\t曜日\t放送局\tタイトル\t公式URL",
        "2026-10-02\t23:00\t金\tTOKYO MX\tSHIROBAKO\thttps://example.com/shirobako"), onlyReply().text());
  }

  @Test
  void findSeasonWithoutWordsListsSeasonWorksWithUnknownDatesAsScheduled() throws Exception {
    annictReturnsWorks(NEXT_SEASON);

    sendText("/find #season 2027-winter");

    assertEquals(String.join("\n",
        "状態\t検索語\t放送開始日\t時刻\t曜日\t放送局\tタイトル\t公式URL",
        "放送予定\t\t\t\t\t\t来季アニメ\t"), onlyReply().text());
    var variables = JSON.readTree(this.wiremock.find(postRequestedFor(urlEqualTo(ANNICT_PATH)))
        .getFirst().getBodyAsString()).path("variables");
    assertEquals(List.of("2027-winter"), JSON.convertValue(variables.path("s"), List.class));
    assertTrue(variables.path("t0").isMissingNode());
  }

  @Test
  void reserveRegistersPastedFindTsvToEdcbAndRepliesWithResultTsv() throws Exception {
    edcbAcceptsAutoAdd("[{\"dataID\":1,\"searchInfo\":{\"andKey\":\"ＳＨＩＲＯＢＡＫＯ\"}}]");

    sendText(String.join("\n", "/reserve",
        "放送開始日\t時刻\t曜日\t放送局\tタイトル\t公式URL",
        "2026-10-02\t23:00\t金\tTOKYO MX\tSHIROBAKO\thttps://example.com/shirobako",
        "2026-10-03\t01:30\t土\tBS11\t葬送のフリーレン 第2期\t",
        "2026-10-03\t01:30\t土"));

    assertEquals(String.join("\n",
        "結果\tタイトル\tキーワード\tEPGヒット\t備考",
        "重複\tSHIROBAKO\tSHIROBAKO\t-\t同じキーワードの自動予約があります",
        "登録\t葬送のフリーレン 第2期\t葬送のフリーレン\t2\t",
        "失敗\t2026-10-03 01:30 土\t\t-\tタイトルがありません"), onlyReply().text());

    // EDCB には認証ヘッダー付きで、キーワードとタイトルごとの保存先が送られる。
    var added = this.wiremock.find(postRequestedFor(urlPathEqualTo(EDCB_PATH + "SetAutoAdd")));
    assertEquals(1, added.size());
    assertEquals("test-client-id", added.getFirst().getHeader("CF-Access-Client-Id"));
    var form = List.of(added.getFirst().getBodyAsString().split("&")).stream()
        .map(pair -> URLDecoder.decode(pair, StandardCharsets.UTF_8)).toList();
    assertTrue(form.contains("andKey=葬送のフリーレン"), form.toString());
    assertTrue(form.contains("recFolder=/record/葬送のフリーレン 第2期"), form.toString());
  }

  @Test
  void reserveRepliesWithFailureRowsWhenEdcbIsUnreachable() throws Exception {
    this.wiremock.register(get(urlPathEqualTo(EDCB_PATH + "EnumAutoAdd"))
        .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

    sendText("/reserve\nSHIROBAKO");

    assertEquals(String.join("\n",
        "結果\tタイトル\tキーワード\tEPGヒット\t備考",
        "失敗\tSHIROBAKO\tSHIROBAKO\t-\tEDCBに接続できません"), onlyReply().text());
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
  void findWithoutWordOrPeriodRepliesWithParseError() throws Exception {
    sendText("/find #format md");

    var text = onlyReply().text();
    assertTrue(text.startsWith("コマンドのパースに失敗しました。"), text);
    assertTrue(text.contains("word か期間（season・from・to）を指定してください"), text);
  }

  @Test
  void unauthorizedUserIsRejectedWithoutCallingAnnict() throws Exception {
    annictReturnsWorks(SHIROBAKO);

    sendWebhook(
        messageEvent(Map.of("type", "text", "id", "1", "text", "/find #word SHIROBAKO"), "test-unauthorized-user"))
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
  // MARK: ヘルパー: Annict のスタブ

  /** Annict の作品検索が、検索語（エイリアス w0, w1…）ごとに指定した作品を返すようにする。引数は作品のJSONをカンマでつないだもの。 */
  private void annictReturnsWorks(String... worksPerWord) {
    var aliases = new ArrayList<String>();
    for(int i = 0; i < worksPerWord.length; i++) {
      aliases.add("\"w%d\":{\"nodes\":[%s]}".formatted(i, worksPerWord[i]));
    }
    this.wiremock.register(post(urlEqualTo(ANNICT_PATH))
        .willReturn(okJson("{\"data\":{" + String.join(",", aliases) + "}}")));
  }

  // ###########################################################################
  // MARK: ヘルパー: EDCB のスタブ

  /** 既存の自動予約を返し、登録・件数確認を受け付けるようにする。件数は常に2件。 */
  private void edcbAcceptsAutoAdd(String autoAdds) {
    this.wiremock.register(get(urlPathEqualTo(EDCB_PATH + "EnumAutoAdd")).willReturn(okJson(autoAdds)));
    this.wiremock.register(get(urlPathEqualTo(EDCB_PATH + "EnumService")).willReturn(okJson("""
        [{"service_name":"ＴＯＫＹＯ　ＭＸ１","onid":32391,"tsid":32391,"sid":23608}]
        """)));
    this.wiremock.register(get(urlPathEqualTo(EDCB_PATH + "EnumRecPreset")).willReturn(okJson("""
        [{"id":0,"name":"Default","recSetting":{"recEnabled":true,"recMode":1,"noRecMode":1,"priority":2,
          "suspendMode":4,"tunerID":0,"partialRecFlag":0,"batFilePath":"","batFileTag":"","serviceMode":0,
          "recFolderList":[{"recFolder":"/record","writePlugIn":"Write_Default.so",
            "recNamePlugIn":"RecName_Macro.so","recNameOp":"$Title$.ts"}]}}]
        """)));
    for(String script : List.of("setautoadd", "searchevent")) {
      this.wiremock.register(get(urlPathEqualTo(EDCB_PATH + "Common")).withQueryParam("ctok", equalTo(script))
          .willReturn(okJson("{\"" + script + "\":\"test-ctok\"}")));
    }
    this.wiremock.register(post(urlPathEqualTo(EDCB_PATH + "SearchEvent"))
        .willReturn(okJson("{\"total\":2,\"index\":0,\"count\":1,\"items\":[]}")));
    this.wiremock.register(post(urlPathEqualTo(EDCB_PATH + "SetAutoAdd"))
        .willReturn(okJson("{\"success\":\"EPG自動予約を追加しました\"}")));
  }

  // ###########################################################################
  // MARK: ヘルパー: LINE への返信の取得

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
