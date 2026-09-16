package app.infrastructure.client;

import static org.junit.jupiter.api.Assertions.*;

import app.usecase.IAnnictClient.SearchCondition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import app.config.AnnictConfig;
import app.usecase.IAnnictClient;
import app.usecase.FindUsecase;
import app.usecase.IInput.FindInput;
import app.usecase.IInput.FindInput.Format;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "ANNICT_LIVE_TEST", matches = "true")
@QuarkusTest
class IAnnictDefLiveTest {
    @Inject IAnnictClient client;
    @Inject AnnictConfig config;
    @Inject FindUsecase findUsecase;

    @Test
    void usecaseQueriesAnnictAndExportsTsv() throws Exception {
        String tsv = findUsecase.run(new FindInput(Format.TSV, List.of("SHIROBAKO"),
            java.time.OffsetDateTime.parse("2010-01-01T00:00:00+09:00")));

        assertTrue(tsv.startsWith("放送開始日\t時刻\t曜日\t放送局\tタイトル\t公式URL\n"));
        assertTrue(tsv.contains("SHIROBAKO"));
        assertTrue(tsv.lines().skip(1).anyMatch(row -> !row.startsWith("\t")));
        assertTrue(tsv.lines().allMatch(row -> row.split("\t", -1).length == 6));

        var output = Path.of("build/reports/annict-poc/find-results.tsv");
        Files.createDirectories(output.getParent());
        Files.writeString(output, tsv);
    }
    @Test
    void generatedClientSearchesAnnictAndExportsFirstBroadcasts() throws Exception {
        assertNotNull(System.getenv("ANNICT_API_TOKEN"));
        var results = client.search(new SearchCondition("SHIROBAKO", List.of(), null, false),
            config.channelPriority());
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(work -> work.firstBroadcast() != null));
        for (var work : results) {
            if (work.firstBroadcast() != null) {
                assertEquals("Asia/Tokyo", work.firstBroadcast().startsAt().getZone().getId());
            }
        }
        var output = Path.of("build/reports/annict-poc/first-broadcast-results.json");
        Files.createDirectories(output.getParent());
        new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .writerWithDefaultPrettyPrinter().writeValue(output.toFile(), results);
    }
}
