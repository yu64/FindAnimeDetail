package app.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 実APIの契約確認用PoC。通常のtestではスキップし、認証情報を出力しない。 */
@EnabledIfEnvironmentVariable(named = "ANNICT_LIVE_TEST", matches = "true")
class AnnictAnimeSearchClientLiveTest {
    @Test
    void searchesExistingAnimeThroughAnnict() throws Exception {
        String token = System.getenv("ANNICT_API_TOKEN");
        assertNotNull(token, "ANNICT_API_TOKEN must be configured");
        assertFalse(token.isBlank(), "ANNICT_API_TOKEN must not be blank");
        String url = System.getenv().getOrDefault("ANNICT_API_URL", "https://api.annict.com/graphql");
        var client = new AnnictAnimeSearchClient(new ObjectMapper(),
            AnnictAnimeSearchClientTest.config(URI.create(url), token));

        var results = client.searchByTitle("SHIROBAKO", 5);
        Path output = Path.of("build", "reports", "annict-poc", "search-results.json");
        Files.createDirectories(output.getParent());
        new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .writerWithDefaultPrettyPrinter().writeValue(output.toFile(), results);

        assertFalse(results.isEmpty(), "Known title should have at least one result");
        assertTrue(results.size() <= 5);
        assertTrue(results.stream().anyMatch(work -> work.title().contains("SHIROBAKO")));
        System.out.printf("Annict PoC: works=%d, officialUrls=%d, broadcasts=%d%n",
            results.size(), results.stream().filter(work -> work.officialSiteUrl() != null).count(),
            results.stream().mapToInt(work -> work.broadcasts().size()).sum());
        for (var work : results) {
            assertTrue(work.annictId() > 0);
            assertFalse(work.title().isBlank());
            for (var broadcast : work.broadcasts()) {
                assertFalse(broadcast.station().isBlank());
                assertNotNull(broadcast.startsAt());
            }
        }
    }
}
