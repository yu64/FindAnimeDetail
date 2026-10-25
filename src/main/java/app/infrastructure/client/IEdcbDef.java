package app.infrastructure.client;

import app.usecase.EdcbException;
import app.usecase.IEdcbClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * EDCB Material WebUI の api/ を呼び出すQuarkus REST Client定義。
 * 書き込み系のAPIはCSRFトークン（ctok）を要求するため、呼び出しごとに api/Common から取得する。
 */
@RegisterRestClient(configKey = "edcb-api")
@RegisterClientHeaders(EdcbHeadersFactory.class)
public interface IEdcbDef extends IEdcbClient {

  // ###########################################################################
  // MARK: 検索条件

  /** ジャンル「アニメ/特撮」の大分類（中分類はすべて）。 */
  public int CONTENT_ANIME = 0x07FF;

  /** 同じ番組を録画済みとみなす日数。別の局での重複録画を防ぐ。 */
  public int CHECK_RECORDED_DAYS = 6;

  // ###########################################################################
  // MARK: HTTP通信の定義

  /** scripts はトークンを発行するスクリプト名（小文字・カンマ区切り）。 */
  @GET
  @Path("/api/Common")
  public Response common(@QueryParam("ctok") String scripts, @QueryParam("json") int json);

  @GET
  @Path("/api/EnumRecPreset")
  public Response enumRecPreset(@QueryParam("json") int json);

  @GET
  @Path("/api/EnumService")
  public Response enumService(@QueryParam("json") int json);

  @GET
  @Path("/api/EnumAutoAdd")
  public Response enumAutoAdd(@QueryParam("json") int json);

  /** count を指定すると、件数（total）と先頭の番組だけを返す。 */
  @POST
  @Path("/api/SearchEvent")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response searchEvent(@QueryParam("json") int json, @QueryParam("count") int count,
      MultivaluedMap<String, String> form);

  @POST
  @Path("/api/SetAutoAdd")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response setAutoAdd(@QueryParam("json") int json, MultivaluedMap<String, String> form);

  // ###########################################################################
  // MARK: 自動予約の取得・件数の確認・登録

  @Override
  public default List<String> autoAddKeywords() {
    List<String> keywords = new ArrayList<>();
    for(JsonNode autoAdd : array(request(() -> this.enumAutoAdd(1)))) {
      keywords.add(autoAdd.path("searchInfo").path("andKey").asText(""));
    }
    return List.copyOf(keywords);
  }

  @Override
  public default int countEvents(String keyword) {
    var form = this.searchKey(keyword);
    form.add("ctok", this.token("searchevent"));

    JsonNode total = request(() -> this.searchEvent(1, 1, form)).path("total");
    if(!total.canConvertToInt()) throw new EdcbException("EDCB search response is missing total");
    return total.asInt();
  }

  @Override
  public default void addAutoAdd(String keyword, String recFolder) {
    var form = this.searchKey(keyword);
    form.putAll(this.recSetting(recFolder));
    form.add("ctok", this.token("setautoadd"));
    form.add("addchg", "1");

    // 入力値が不足していても HTTP 200 で {"err": …} が返るため、本文で成否を判定する。
    JsonNode result = request(() -> this.setAutoAdd(1, form));
    if(!result.path("success").isTextual()) {
      throw new EdcbException("EDCB rejected auto-add: " + result.path("err").asText("unknown"));
    }
  }

  // ###########################################################################
  // MARK: フォームの組み立て

  /**
   * 件数の確認と登録で共通の検索条件。真偽値の項目はEDCBが有無だけで判定するため、偽なら送らない。
   * 局は全局を明示する。指定しないとどの局にも一致しない。
   */
  private MultivaluedMap<String, String> searchKey(String keyword) {
    var form = new MultivaluedHashMap<String, String>();
    form.add("andKey", keyword);
    form.add("aimaiFlag", "1");
    form.add("contentList", String.valueOf(CONTENT_ANIME));
    form.add("chkRecEnd", "1");
    form.add("chkRecDay", String.valueOf(CHECK_RECORDED_DAYS));

    for(JsonNode service : array(request(() -> this.enumService(1)))) {
      form.add("serviceList", "%d-%d-%d".formatted(
          service.path("onid").asInt(), service.path("tsid").asInt(), service.path("sid").asInt()));
    }
    if(!form.containsKey("serviceList")) throw new EdcbException("EDCB returned no services");
    return form;
  }

  /**
   * 既定プリセット（id=0）の録画設定を、SetAutoAdd のフォームに変換する。
   * 送らなかった項目はプリセットではなく初期値になるため、すべての項目を送る。
   */
  private MultivaluedMap<String, String> recSetting(String recFolder) {
    JsonNode rs = null;
    for(JsonNode preset : array(request(() -> this.enumRecPreset(1)))) {
      if(preset.path("id").asInt(-1) == 0) rs = preset.path("recSetting");
    }
    if(rs == null || !rs.isObject()) throw new EdcbException("EDCB default recording preset is missing");

    var form = new MultivaluedHashMap<String, String>();
    // 録画モードは recEnabled が有効なときだけ使われ、無効なら「無効」になる。
    if(rs.path("recEnabled").asBoolean()) form.add("recEnabled", "1");
    form.add("recMode", rs.path("recEnabled").asBoolean()
        ? rs.path("recMode").asText()
        : rs.path("noRecMode").asText());
    form.add("priority", rs.path("priority").asText());
    form.add("suspendMode", rs.path("suspendMode").asText());
    form.add("tunerID", rs.path("tunerID").asText());
    form.add("partialRecFlag", rs.path("partialRecFlag").asText("0"));
    form.add("batFilePath", rs.path("batFilePath").asText(""));
    form.add("batFileTag", rs.path("batFileTag").asText(""));

    // 値の0もLuaでは真になるため、偽の項目は送らない。
    for(String flag : List.of("tuijyuuFlag", "pittariFlag", "rebootFlag", "continueRecFlag")) {
      if(rs.path(flag).asBoolean()) form.add(flag, "1");
    }

    // マージンがなければEDCBの既定値を使う。
    if(rs.has("startMargin") && rs.has("endMargin")) {
      form.add("startMargin", rs.path("startMargin").asText());
      form.add("endMargin", rs.path("endMargin").asText());
    } else {
      form.add("useDefMarginFlag", "1");
    }

    // serviceMode は bit0 が「個別指定」、bit4・bit5 が字幕・データカルーセル。
    int serviceMode = rs.path("serviceMode").asInt();
    if((serviceMode & 1) == 0) {
      form.add("serviceMode", "1");
    } else {
      form.add("serviceMode_1", String.valueOf((serviceMode >> 4) & 1));
      form.add("serviceMode_2", String.valueOf((serviceMode >> 5) & 1));
    }

    // 保存先だけを差し替え、出力プラグインとファイル名の書式はプリセットに揃える。
    JsonNode folder = rs.path("recFolderList").path(0);
    form.add("recFolder", recFolder);
    form.add("writePlugIn", folder.path("writePlugIn").asText(""));
    form.add("recNamePlugIn", folder.path("recNamePlugIn").asText(""));
    form.add("recName", folder.path("recNameOp").asText(""));
    return form;
  }

  /** 指定スクリプト用のCSRFトークンを取得する。 */
  private String token(String script) {
    String token = request(() -> this.common(script, 1)).path(script).asText("");
    if(token.isBlank()) throw new EdcbException("EDCB did not issue a CSRF token");
    return token;
  }

  // ###########################################################################
  // MARK: 応答の検証

  /**
   * HTTP 200 のJSONだけを受け付ける。認証に失敗した場合のログイン画面への転送やHTMLも、ここで失敗として扱う。
   * 秘密値を含みうるため、応答本文は例外に含めない。
   */
  private static JsonNode request(Supplier<Response> call) {
    try (Response response = call.get()) {
      if(response.getStatus() != 200) {
        throw new EdcbException("EDCB returned HTTP " + response.getStatus());
      }
      var type = response.getMediaType();
      if(type == null || !type.getSubtype().toLowerCase().contains("json")) {
        throw new EdcbException("EDCB returned a non-JSON response; authentication may have failed");
      }
      return new ObjectMapper().readTree(response.readEntity(String.class));
    } catch(EdcbException e) {
      throw e;
    } catch(Exception e) {
      throw new EdcbException("Could not call EDCB API", e);
    }
  }

  /** 配列でなければ異常な応答として扱う。 */
  private static JsonNode array(JsonNode node) {
    if(!node.isArray()) throw new EdcbException("EDCB response is not an array");
    return node;
  }

  // ###########################################################################
  // MARK: ユースケース向けインターフェイスへのDI中継

  /** 生成されたREST Clientを、IEdcbClientとして注入できるようにする。 */
  @ApplicationScoped
  public class DiRelay {

    @Produces
    @ApplicationScoped
    public IEdcbClient relay(@RestClient IEdcbDef client) {
      return client;
    }
  }
}
