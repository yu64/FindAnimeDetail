package app.infrastructure.client;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

import app.usecase.EdcbException;
import app.usecase.IEdcbClient;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.quarkiverse.wiremock.devservice.ConnectWireMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** モックテスト: EDCB Material WebUI の API を WireMock に置き換え、REST Client の送受信を確認する。 */
@QuarkusTest
@ConnectWireMock
class IEdcbDefMockTest {

  private static final String BASE = "/edcb/api/";

  /** 実機の EnumRecPreset と同じ形の既定プリセット。 */
  private static final String PRESETS = """
      [{"name":"Default","id":0,"recSetting":{"continueRecFlag":true,"startMargin":15,"endMargin":10,
        "partialRecFolder":[],"batFileTag":"","priority":2,"partialRecFlag":0,
        "recFolderList":[{"recNameOp":"$SDYY$_$ZtoH(Title)$.ts","recFolder":"/record",
          "recNamePlugIn":"RecName_Macro.so","writePlugIn":"Write_Default.so"}],
        "tunerID":0,"pittariFlag":false,"recMode":1,"suspendMode":4,"rebootFlag":false,"batFilePath":"",
        "recEnabled":true,"tuijyuuFlag":true,"serviceMode":0,"noRecMode":1}}]
      """;

  private static final String SERVICES = """
      [{"service_name":"ＴＯＫＹＯ　ＭＸ１","onid":32391,"tsid":32391,"sid":23608,"service_type":1},
       {"service_name":"ＢＳ１１イレブン","onid":4,"tsid":16528,"sid":211,"service_type":1}]
      """;

  WireMock wiremock;

  @Inject
  IEdcbClient edcb;

  @BeforeEach
  void reset() {
    this.wiremock.resetRequests();
    this.wiremock.resetMappings();
  }

  // ###########################################################################
  // MARK: 正常系

  @Test
  void addAutoAddSendsSearchKeyAndPresetWithFolderReplaced() {
    stubReads();
    this.wiremock.register(post(urlPathEqualTo(BASE + "SetAutoAdd")).withQueryParam("json", equalTo("1"))
        .willReturn(okJson("{\"success\":\"EPG自動予約を追加しました\"}")));

    this.edcb.addAutoAdd("ぼっち・ざ・ろっく！", "/record/ぼっち・ざ・ろっく！");

    var form = form(onlyRequest(postRequestedFor(urlPathEqualTo(BASE + "SetAutoAdd"))));
    // 検索条件: 曖昧検索・アニメ/特撮・全局・録画済みチェック。
    assertEquals(List.of("ぼっち・ざ・ろっく！"), form.get("andKey"));
    assertEquals(List.of("1"), form.get("aimaiFlag"));
    assertEquals(List.of("2047"), form.get("contentList"));
    assertEquals(List.of("32391-32391-23608", "4-16528-211"), form.get("serviceList"));
    assertEquals(List.of("1"), form.get("chkRecEnd"));
    assertEquals(List.of("6"), form.get("chkRecDay"));
    // 録画設定はプリセットのまま。偽の項目は送らない。
    assertEquals(List.of("1"), form.get("recEnabled"));
    assertEquals(List.of("1"), form.get("recMode"));
    assertEquals(List.of("2"), form.get("priority"));
    assertEquals(List.of("4"), form.get("suspendMode"));
    assertEquals(List.of("0"), form.get("tunerID"));
    assertEquals(List.of("1"), form.get("tuijyuuFlag"));
    assertEquals(List.of("1"), form.get("continueRecFlag"));
    assertNull(form.get("pittariFlag"));
    assertNull(form.get("rebootFlag"));
    assertEquals(List.of("15"), form.get("startMargin"));
    assertEquals(List.of("10"), form.get("endMargin"));
    assertNull(form.get("useDefMarginFlag"));
    assertEquals(List.of("1"), form.get("serviceMode"));
    // 保存先だけを差し替える。
    assertEquals(List.of("/record/ぼっち・ざ・ろっく！"), form.get("recFolder"));
    assertEquals(List.of("Write_Default.so"), form.get("writePlugIn"));
    assertEquals(List.of("RecName_Macro.so"), form.get("recNamePlugIn"));
    assertEquals(List.of("$SDYY$_$ZtoH(Title)$.ts"), form.get("recName"));
    // 登録用のトークンと追加の指示。
    assertEquals(List.of("test-ctok-setautoadd"), form.get("ctok"));
    assertEquals(List.of("1"), form.get("addchg"));
  }

  @Test
  void countEventsUsesSameSearchKeyWithSearchToken() {
    stubReads();
    this.wiremock.register(post(urlPathEqualTo(BASE + "SearchEvent"))
        .withQueryParam("json", equalTo("1")).withQueryParam("count", equalTo("1"))
        .willReturn(okJson("{\"total\":3,\"index\":0,\"count\":1,\"items\":[]}")));

    assertEquals(3, this.edcb.countEvents("SHIROBAKO"));

    var form = form(onlyRequest(postRequestedFor(urlPathEqualTo(BASE + "SearchEvent"))));
    assertEquals(List.of("SHIROBAKO"), form.get("andKey"));
    assertEquals(List.of("2047"), form.get("contentList"));
    assertEquals(List.of("32391-32391-23608", "4-16528-211"), form.get("serviceList"));
    assertEquals(List.of("test-ctok-searchevent"), form.get("ctok"));
  }

  @Test
  void autoAddKeywordsReturnsKeywordsOfExistingAutoAdds() {
    this.wiremock.register(get(urlPathEqualTo(BASE + "EnumAutoAdd")).willReturn(okJson("""
        [{"dataID":1,"searchInfo":{"andKey":"SHIROBAKO","disableFlag":false}},
         {"dataID":2,"searchInfo":{"andKey":"ぼっち・ざ・ろっく！","disableFlag":true}}]
        """)));

    assertEquals(List.of("SHIROBAKO", "ぼっち・ざ・ろっく！"), this.edcb.autoAddKeywords());
  }

  @Test
  void sendsConfiguredHeadersOnEveryRequest() {
    stubReads();
    this.wiremock.register(post(urlPathEqualTo(BASE + "SetAutoAdd")).willReturn(okJson("{\"success\":\"ok\"}")));

    this.edcb.addAutoAdd("SHIROBAKO", "/record/SHIROBAKO");

    var requests = this.wiremock.find(anyRequestedFor(urlPathMatching(BASE + ".*")));
    // EnumService・EnumRecPreset・Common・SetAutoAdd
    assertEquals(4, requests.size());
    for(var request : requests) {
      assertEquals("test-client-id", request.getHeader("CF-Access-Client-Id"), request.getUrl());
      assertEquals("test-client-secret", request.getHeader("CF-Access-Client-Secret"), request.getUrl());
    }
  }

  // ###########################################################################
  // MARK: 異常系

  @Test
  void rejectedAutoAddThrowsWithEdcbMessage() {
    stubReads();
    this.wiremock.register(post(urlPathEqualTo(BASE + "SetAutoAdd")).willReturn(okJson("{\"err\":\"不正値入力\"}")));

    var e = assertThrows(EdcbException.class, () -> this.edcb.addAutoAdd("SHIROBAKO", "/record/SHIROBAKO"));
    assertTrue(e.getMessage().contains("不正値入力"), e.getMessage());
  }

  @Test
  void authenticationAndServerFailuresThrowWithoutResponseBody() {
    var failures = List.of(
        // Cloudflare Access の認証失敗はログイン画面へ転送される。
        aResponse().withStatus(302).withHeader("Location", "https://example.cloudflareaccess.com/login"),
        aResponse().withStatus(200).withHeader("Content-Type", "text/html").withBody("<html>secret-detail</html>"),
        aResponse().withStatus(500).withBody("secret-detail"),
        aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER));

    for(var failure : failures) {
      reset();
      this.wiremock.register(get(urlPathEqualTo(BASE + "EnumAutoAdd")).willReturn(failure));

      var e = assertThrows(EdcbException.class, () -> this.edcb.autoAddKeywords());
      assertFalse(String.valueOf(e.getMessage()).contains("secret-detail"), e.getMessage());
    }
  }

  @Test
  void missingTokenOrPresetStopsBeforeWriting() {
    stubReads();
    this.wiremock.register(get(urlPathEqualTo(BASE + "Common")).willReturn(okJson("{}")));
    assertThrows(EdcbException.class, () -> this.edcb.addAutoAdd("SHIROBAKO", "/record/SHIROBAKO"));

    reset();
    stubReads();
    this.wiremock.register(get(urlPathEqualTo(BASE + "EnumRecPreset")).willReturn(okJson("[]")));
    assertThrows(EdcbException.class, () -> this.edcb.addAutoAdd("SHIROBAKO", "/record/SHIROBAKO"));

    assertEquals(0, this.wiremock.find(postRequestedFor(urlPathEqualTo(BASE + "SetAutoAdd"))).size());
  }

  // ###########################################################################
  // MARK: ヘルパー

  /** 登録・件数確認の前に読む API を、実機と同じ形で返すようにする。 */
  private void stubReads() {
    for(String script : List.of("setautoadd", "searchevent")) {
      this.wiremock.register(common(script));
    }
    this.wiremock.register(get(urlPathEqualTo(BASE + "EnumRecPreset")).willReturn(okJson(PRESETS)));
    this.wiremock.register(get(urlPathEqualTo(BASE + "EnumService")).willReturn(okJson(SERVICES)));
  }

  private static MappingBuilder common(String script) {
    return get(urlPathEqualTo(BASE + "Common")).withQueryParam("ctok", equalTo(script))
        .willReturn(okJson("{\"" + script + "\":\"test-ctok-" + script + "\"}"));
  }

  private LoggedRequest onlyRequest(RequestPatternBuilder pattern) {
    var requests = this.wiremock.find(pattern);
    assertEquals(1, requests.size());
    return requests.getFirst();
  }

  /** フォーム本文を、同じ名前の値を順に保持したまま復元する。 */
  private static Map<String, List<String>> form(LoggedRequest request) {
    assertTrue(request.getHeader("Content-Type").startsWith("application/x-www-form-urlencoded"),
        request.getHeader("Content-Type"));
    var form = new LinkedHashMap<String, List<String>>();
    for(String pair : request.getBodyAsString().split("&")) {
      var kv = pair.split("=", 2);
      form.computeIfAbsent(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), key -> new ArrayList<>())
          .add(kv.length < 2 ? "" : URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
    }
    return form;
  }
}
