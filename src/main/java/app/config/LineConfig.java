package app.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.List;

@ConfigMapping(prefix = "line-bot")
public interface LineConfig {

  @WithDefault("dummy-line-bot-channel-secret")
  public String channelSecret();

  @WithDefault("dummy-line-bot-channel-token")
  public String channelToken();

  /** ボットを利用できるLINEのuserId。環境変数: LINE_BOT_ALLOWED_USER_IDS（カンマ区切り）。既定値は実在しないため、未設定なら誰も利用できない。 */
  @WithDefault("dummy-line-bot-allowed-user-id")
  public List<String> allowedUserIds();
}
