package app.presentation.controller;

import static org.junit.jupiter.api.Assertions.*;

import app.config.AnnictConfig;
import app.infrastructure.AnimeSearchException;
import app.infrastructure.MugCommandSyntaxParser;
import app.presentation.ILineClient;
import app.presentation.command.def.CommandDefRegistry;
import app.presentation.command.def.FindCommandDef;
import app.presentation.command.def.HelpCommandDef;
import app.usecase.FindUsecase;
import app.usecase.IAnnictClient;
import app.usecase.help.HelpUsecase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.linecorp.bot.model.event.CallbackRequest;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LineControllerTest {
  private final AnnictConfig config = new AnnictConfig() {
    public URI url() { return URI.create("https://example.com/graphql"); }
    public String token() { return "test-token"; }
    public List<Integer> channelPriority() { return List.of(19); }
  };

  private LineController controller(IAnnictClient annict, ILineClient line) {
    var registry = new CommandDefRegistry(List.of(new FindCommandDef(), new HelpCommandDef()));
    return new LineController(line, new MugCommandSyntaxParser(), registry,
      new HelpUsecase(registry), new FindUsecase(annict, config));
  }

  private CallbackRequest request(String format) throws Exception {
    return new ObjectMapper().findAndRegisterModules()
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue("""
      {"destination":"test","events":[{
        "type":"message","replyToken":"test-reply","timestamp":0,
        "source":{"type":"user","userId":"test-user"},"mode":"active",
        "webhookEventId":"test-event","deliveryContext":{"isRedelivery":false},
        "message":{"type":"text","id":"1","text":"/find #format %s #word anime"}
      }]}
      """.formatted(format), CallbackRequest.class);
  }

  @Test
  void repliesWithRateLimitNoticeForBothFormats() throws Exception {
    for (String format : List.of("tsv", "md")) {
      var replies = new ArrayList<String>();
      IAnnictClient annict = (condition, priorities) -> {
        throw new AnimeSearchException("API failed", new RuntimeException(
          new WebApplicationException(Response.status(429).build())));
      };
      var controller = controller(annict, (token, text) -> {
        assertEquals("test-reply", token);
        replies.add(text);
      });
      assertEquals(200, controller.handle(request(format)).getStatus());
      assertEquals(List.of("Annictのアクセス制限により検索できませんでした。しばらく時間を置いてから再検索してください。"), replies);
    }
  }

  @Test
  void otherFailuresReplyWithoutExposingExceptionDetails() throws Exception {
    for (RuntimeException failure : List.of(
        new AnimeSearchException("secret-detail", new WebApplicationException(503)),
        new IllegalStateException("secret-detail"))) {
      var replies = new ArrayList<String>();
      var controller = controller((condition, priorities) -> { throw failure; },
        (token, text) -> replies.add(text));
      assertEquals(200, controller.handle(request("tsv")).getStatus());
      assertEquals(1, replies.size());
      assertFalse(replies.getFirst().contains("secret-detail"));
      assertFalse(replies.getFirst().contains("アクセス制限"));
      assertTrue(replies.getFirst().contains("しばらく時間を置いて"));
    }
  }

  @Test
  void normalResultsStillReplyAndReplyFailuresAreNotRetried() throws Exception {
    var replies = new ArrayList<String>();
    var controller = controller((condition, priorities) -> List.of(), (token, text) -> {
      replies.add(text);
      throw new IllegalStateException("reply failed");
    });
    assertThrows(IllegalStateException.class, () -> controller.handle(request("tsv")));
    assertEquals(List.of("放送開始日\t時刻\t曜日\t放送局\tタイトル\t公式URL"), replies);
  }
}
