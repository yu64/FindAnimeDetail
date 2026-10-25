package app.infrastructure.client;

import app.config.EdcbConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import java.util.Map;
import org.eclipse.microprofile.rest.client.ext.ClientHeadersFactory;

/** EDCB_API_HEADERS のJSONを、EDCBへの全リクエストのヘッダーにする。Cloudflare Access などの認証に使う。 */
@ApplicationScoped
public class EdcbHeadersFactory implements ClientHeadersFactory {

  private final MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();

  /** 設定は起動時に一度だけ解釈する。不正なJSONでも値を含めずに失敗させる。 */
  @Inject
  public EdcbHeadersFactory(EdcbConfig config) {
    JsonNode root;
    try {
      root = new ObjectMapper().readTree(config.headers());
    } catch(JsonProcessingException e) {
      throw new IllegalStateException("EDCB_API_HEADERS must be a JSON object");
    }
    if(root == null || !root.isObject()) {
      throw new IllegalStateException("EDCB_API_HEADERS must be a JSON object");
    }
    for(Map.Entry<String, JsonNode> header : root.properties()) {
      this.headers.add(header.getKey(), header.getValue().asText());
    }
  }

  @Override
  public MultivaluedMap<String, String> update(
      MultivaluedMap<String, String> incomingHeaders,
      MultivaluedMap<String, String> clientOutgoingHeaders) {
    return this.headers;
  }
}
