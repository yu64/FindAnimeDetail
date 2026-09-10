package app.presentation.controller;


import com.linecorp.bot.model.event.CallbackRequest;
import com.linecorp.bot.model.event.MessageEvent;
import com.linecorp.bot.model.event.message.TextMessageContent;

import app.presentation.ICommandParser;
import app.util.IResult;
import app.presentation.mapper.CommandMapper;
import app.presentation.mapper.ParsedCommand;
import app.usecase.ILineClient;
import app.usecase.command.ICommand;
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
  private final ICommandParser parser;
  private final CommandMapper mapper;

  @Inject
  public LineController(
    ILineClient line,
    ICommandParser parser,
    CommandMapper mapper
  )
  {
    this.line = line;
    this.parser = parser;
    this.mapper = mapper;
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

    req.getEvents()
      .parallelStream()
      .filter(event -> event instanceof MessageEvent<?>)
      .map(event -> (MessageEvent<?>) event)
      .forEach(this::onMessage);

    return Response.ok().build();
  }

  /** 各メッセージごとに呼び出される */
  private void onMessage(MessageEvent<?> messageEvent)
  {
    if(!(messageEvent.getMessage() instanceof TextMessageContent textMessageContent))
    {
      return;
    }

    // LINEから入力文字列と送信トークンを取得
    String userText = textMessageContent.getText();
    String replyToken = messageEvent.getReplyToken();

    // コマンドパース
    IResult<ParsedCommand, String> parseResult = this.parser.parse(userText);
    if(parseResult instanceof IResult.Err(var error))
    {
      var msg = (
        "コマンドのパースに失敗しました。\n"
        + "ヘルプが必要な場合は `/help` を実行してください。\n"
        + "\n"
        + error
      );
      this.line.reply(replyToken, msg);
      return;
    }

    // コマンドパースに成功したら、実在するコマンドにマッピング
    IResult<ICommand, String> cmdResult = this.mapper.map(parseResult.ok().val());
    if(cmdResult instanceof IResult.Err(var error))
    {
      var msg = (
        "コマンドのパースに失敗しました。\n"
        + "ヘルプが必要な場合は `/help` を実行してください。\n"
        + "\n"
        + error
      );
      this.line.reply(replyToken, msg);
      return;
    }

    ICommand cmd = cmdResult.ok().val();
    this.line.reply(replyToken, cmd.toString());
  }
}
