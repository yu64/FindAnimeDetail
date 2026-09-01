package app.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.net.URI;

@ConfigMapping(prefix = "annict-api")
public interface AnnictConfig {

  @WithDefault("https://api.annict.com/graphql")
  URI url();

  @WithDefault("prototype-token-not-configured")
  String token();
}
