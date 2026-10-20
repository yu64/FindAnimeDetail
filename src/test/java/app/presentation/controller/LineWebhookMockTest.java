package app.presentation.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.client.WireMock;
import io.quarkiverse.wiremock.devservice.ConnectWireMock;
import io.quarkus.test.junit.QuarkusTest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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

  WireMock wiremock;

  @BeforeEach
  void reset() {
    this.wiremock.resetRequests();
    this.wiremock.resetMappings();
    this.wiremock.register(post(urlEqualTo("/v2/bot/message/reply")).willReturn(ok()));
  }

  @Test
  void acceptsConnectivityCheckWithoutReplying() throws Exception {
    sendWebhook("""
        {"destination":"test","events":[]}
        """).then().statusCode(200);

    assertEquals(0, this.wiremock.find(postRequestedFor(urlEqualTo("/v2/bot/message/reply"))).size());
  }

  @Test
  void rejectsRequestWithoutSignature() {
    given().contentType("application/json")
        .body("{\"destination\":\"test\",\"events\":[]}")
        .post("/line/webhook")
        .then().statusCode(401);
  }

  // ###########################################################################
  // MARK: ヘルパー

  /** LINE プラットフォームと同じ方法で署名し、Webhook を送る。 */
  private static io.restassured.response.Response sendWebhook(String json) throws Exception {
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
}
