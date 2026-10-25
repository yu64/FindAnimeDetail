package app.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.net.URI;

@ConfigMapping(prefix = "edcb-api")
public interface EdcbConfig {

  /** EDCB Material WebUI のベースURL。環境変数: EDCB_API_URL */
  @WithDefault("http://localhost:5510")
  URI url();

  /** リクエストに付けるヘッダー（JSONオブジェクト）。環境変数: EDCB_API_HEADERS。値はログに出さない。 */
  @WithDefault("{}")
  String headers();
}
