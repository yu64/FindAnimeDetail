package app.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.net.URI;
import java.util.List;

@ConfigMapping(prefix = "annict-api")
public interface AnnictConfig {

  /** GraphQL APIのURL。環境変数: ANNICT_API_URL */
  @WithDefault("https://api.annict.com/graphql")
  URI url();

  /** アクセストークン。環境変数: ANNICT_API_TOKEN */
  @WithDefault("prototype-token-not-configured")
  String token();

  /**
   * 放送局の優先順。環境変数: ANNICT_API_CHANNEL_PRIORITY（カンマ区切り）
   * 東京向けの既定値: TOKYO MX、MX2、テレビ東京、TBS、日本テレビ、
   * テレビ朝日、フジテレビ、NHK Eテレ、NHK総合。
   * IDはAnnictのchannel.annictIdを使用する。
   */
  @WithDefault("19,188,7,5,4,6,3,2,1")
  List<Integer> channelPriority();
}
