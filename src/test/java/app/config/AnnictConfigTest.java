package app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnnictConfigTest {
    @Test
    void usesTokyoDefaults() {
        var config = new SmallRyeConfigBuilder()
            .withMapping(AnnictConfig.class).build().getConfigMapping(AnnictConfig.class);

        assertEquals(URI.create("https://api.annict.com/graphql"), config.url());
        assertEquals(List.of(19, 188, 7, 5, 4, 6, 3, 2, 1), config.channelPriority());
    }

    @Test
    void readsEnvironmentVariablesAndPreservesPriorityOrder() {
        var config = new SmallRyeConfigBuilder()
            .withSources(new EnvConfigSource(Map.of(
                "ANNICT_API_URL", "https://example.com/graphql",
                "ANNICT_API_TOKEN", "test-token",
                "ANNICT_API_CHANNEL_PRIORITY", "7,19,1"), 300))
            .withMapping(AnnictConfig.class).build().getConfigMapping(AnnictConfig.class);

        assertEquals(URI.create("https://example.com/graphql"), config.url());
        assertEquals("test-token", config.token());
        assertEquals(List.of(7, 19, 1), config.channelPriority());
    }
}
