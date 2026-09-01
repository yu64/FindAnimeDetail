package app.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "line-bot")
public interface LineConfig {

  @WithDefault("dummy-line-bot-channel-secret")
  public String channelSecret();

  @WithDefault("dummy-line-bot-channel-token")
  public String channelToken();
}
