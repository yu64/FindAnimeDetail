package app.presentation.controller;


import com.linecorp.bot.model.event.CallbackRequest;
import com.linecorp.bot.model.event.MessageEvent;
import com.linecorp.bot.model.event.message.TextMessageContent;

import app.usecase.ILineClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
@Path("/line")
public class LineController {

  private final ILineClient line;

  @Inject
  public LineController(
    ILineClient line
  )
  {
    this.line = line;
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Path("/webhook")
  public Response handle(CallbackRequest req)
  {
    if(req == null || req.getEvents() == null)
    {
      return Response.ok().build();
    }

    for(var event : req.getEvents())
    {
      if(!(event instanceof MessageEvent<?> messageEvent))
      {
        continue;
      }

      if(!(messageEvent.getMessage() instanceof TextMessageContent textMessageContent))
      {
        continue;
      }

      String userText = textMessageContent.getText();
      String replyToken = messageEvent.getReplyToken();

      this.line.reply(replyToken, userText);
    }

    return Response.ok().build();
  }
}
