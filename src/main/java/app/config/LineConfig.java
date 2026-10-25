package app.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.List;
import java.util.Optional;

@ConfigMapping(prefix = "line-bot")
public interface LineConfig {

  @WithDefault("dummy-line-bot-channel-secret")
  public String channelSecret();

  @WithDefault("dummy-line-bot-channel-token")
  public String channelToken();

  /** ボットを利用できるLINEのuserId。環境変数: LINE_BOT_ALLOWED_USER_IDS（カンマ区切り）。未設定なら誰も利用できない。 */
  public Optional<List<String>> allowedUserIds();
}
