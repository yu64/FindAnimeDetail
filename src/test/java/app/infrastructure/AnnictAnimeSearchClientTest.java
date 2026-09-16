package app.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import app.config.AnnictConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 外部APIや認証情報に依存せず、公開メソッドから実際のHTTP通信まで検証する。 */
class AnnictAnimeSearchClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final LinkedBlockingQueue<CapturedRequest> requests = new LinkedBlockingQueue<>();
    private HttpServer server;
    private AnnictAnimeSearchClient client;
    private volatile int status = 200;
    private volatile String body = """
        {"data":{"searchWorks":{"edges":[]}}}
        """;

    record CapturedRequest(String method, String path, String authorization,
                           String contentType, String body) {}

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/graphql", exchange -> {
            try (exchange) {
                requests.add(new CapturedRequest(exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        client = new AnnictAnimeSearchClient(mapper, config(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/graphql"),
            "test-token"));
    }

    static AnnictConfig config(URI endpoint, String token) {
        return new AnnictConfig() {
            public URI url() { return endpoint; }
            public String token() { return token; }
            public List<Integer> channelPriority() { return List.of(); }
        };
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void postsGraphQlVariablesAndMapsWorksAndBroadcasts() throws Exception {
        body = """
            {"data":{"searchWorks":{"edges":[
              {"node":{"annictId":123,"title":"テストアニメ",
                "officialSiteUrl":"https://example.com/anime",
                "programs":{"edges":[
                  {"node":{"startedAt":"2026-09-11T23:30:00+09:00",
                    "channel":{"name":"テスト放送局"},
                    "episode":{"numberText":"第1話","title":"はじまり"}}},
                  {"node":{"startedAt":"2026-09-18T14:30:00Z",
                    "channel":{"name":"テスト放送局"},"episode":null}}
                ]}}},
              {"node":{"annictId":456,"title":"別作品","officialSiteUrl":null,
                "programs":{"edges":[]}}}
            ]}}}
            """;

        var results = client.searchByTitle("  テスト\"アニメ  ", 2);

        assertEquals(List.of(
            new AnimeSearchResult(123, "テストアニメ", URI.create("https://example.com/anime"), List.of(
                new AnimeSearchResult.Broadcast("テスト放送局",
                    OffsetDateTime.parse("2026-09-11T23:30:00+09:00"), "第1話", "はじまり"),
                new AnimeSearchResult.Broadcast("テスト放送局",
                    OffsetDateTime.parse("2026-09-18T14:30:00Z"), null, null))),
            new AnimeSearchResult(456, "別作品", null, List.of())), results);
        var request = requests.poll(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("POST", request.method());
        assertEquals("/graphql", request.path());
        assertEquals("Bearer test-token", request.authorization());
        assertEquals("application/json", request.contentType());
        JsonNode payload = mapper.readTree(request.body());
        assertEquals(mapper.readTree("""
            {"titles":["テスト\\\"アニメ"],"limit":2}
            """), payload.get("variables"));
        assertTrue(payload.path("query").asText().contains("searchWorks"));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 50})
    void acceptsLimitBoundariesAndEmptyResults(int limit) {
        assertEquals(List.of(), client.searchByTitle("該当なし", limit));
    }

    @Test
    void rejectsInvalidInputBeforeSendingRequest() {
        for (String title : new String[] {null, "", "  "}) {
            assertThrows(IllegalArgumentException.class, () -> client.searchByTitle(title, 1));
        }
        for (int limit : new int[] {-1, 0, 51}) {
            assertThrows(IllegalArgumentException.class, () -> client.searchByTitle("テスト", limit));
        }
        assertTrue(requests.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 429, 500})
    void rejectsHttpErrors(int httpStatus) {
        status = httpStatus;
        body = "upstream error";
        var error = assertThrows(AnimeSearchException.class, () -> client.searchByTitle("テスト", 1));
        assertEquals("Annict API returned HTTP " + httpStatus, error.getMessage());
    }

    @Test
    void rejectsGraphQlErrorsEvenWithHttp200AndPartialData() {
        body = """
            {"data":{"searchWorks":{"edges":[]}},"errors":[{"message":"Invalid query"}]}
            """;
        var error = assertThrows(AnimeSearchException.class, () -> client.searchByTitle("テスト", 1));
        assertEquals("Annict GraphQL error: Invalid query", error.getMessage());
    }

    @Test
    void wrapsMalformedJson() {
        body = "not json";
        var error = assertThrows(AnimeSearchException.class, () -> client.searchByTitle("テスト", 1));
        assertInstanceOf(java.io.IOException.class, error.getCause());
    }
}
