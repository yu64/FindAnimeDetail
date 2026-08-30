package app.presentation.controller;

import java.util.stream.Stream;

import com.linecorp.bot.model.event.CallbackRequest;
import com.linecorp.bot.model.event.MessageEvent;
import com.linecorp.bot.model.event.message.TextMessageContent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
@Path("/line")
public class LineController {

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Path("/webhook")
  public Response handle(CallbackRequest req)
  {
    Stream<TextMessageContent> ctxs = req
      .getEvents()
      .stream()
      .filter(e -> e instanceof MessageEvent)
      .map(e -> (MessageEvent<?>) e)
      .filter(e -> e.getMessage() instanceof TextMessageContent)
      .map(e -> (TextMessageContent) e.getMessage());

    for(var ctx : ((Iterable<TextMessageContent>) (() -> ctxs.iterator())))
    {
      String userText = ctx.getText();
      System.out.println("届いたメッセージ: " + userText);
    }

    return Response.ok().build();
  }
}
