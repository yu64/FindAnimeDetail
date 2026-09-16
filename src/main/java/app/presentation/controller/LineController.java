package app.presentation.controller;


import com.linecorp.bot.model.event.CallbackRequest;
import com.linecorp.bot.model.event.MessageEvent;
import com.linecorp.bot.model.event.message.TextMessageContent;

import app.util.IResult;
import app.infrastructure.AnimeSearchException;
import io.quarkus.logging.Log;
import app.usecase.IInput.FindInput;
import app.usecase.IInput.HelpInput;
import app.usecase.help.HelpUsecase;
import app.usecase.IInput;
import app.presentation.ILineClient;
import app.presentation.command.def.CommandDefRegistry;
import app.presentation.command.parse.ICommandSyntaxParser;
import app.presentation.command.parse.CommandExpression;
import app.usecase.FindUsecase;
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
  private final ICommandSyntaxParser parser;
  private final CommandDefRegistry registry;
  private final HelpUsecase helpUsecase;
  private final FindUsecase findUsecase;

  @Inject
  public LineController(
    ILineClient line,
    ICommandSyntaxParser parser,
    CommandDefRegistry registry,
    HelpUsecase helpUsecase,
    FindUsecase findUsecase
  )
  {
    this.line = line;
    this.parser = parser;
    this.registry = registry;
    this.helpUsecase = helpUsecase;
    this.findUsecase = findUsecase;
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
    IResult<CommandExpression, String> parseResult = this.parser.parse(userText);
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
    IResult<IInput, String> cmdResult = this.registry.map(parseResult.ok().val());
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

    // ユースケースに振り分けて実行
    IInput cmd = cmdResult.ok().val();
    String msg;
    try {
      msg = switch(cmd) {
        case HelpInput c -> helpUsecase.run(c);
        case FindInput c -> findUsecase.run(c);
      };
    } catch (AnimeSearchException e) {
      Log.error("Anime search failed", e);
      msg = e.isRateLimited()
        ? "Annictのアクセス制限により検索できませんでした。しばらく時間を置いてから再検索してください。"
        : "アニメ情報の取得に失敗しました。しばらく時間を置いてから再検索してください。";
    } catch (RuntimeException e) {
      Log.error("Command execution failed", e);
      msg = "処理中にエラーが発生しました。しばらく時間を置いてから再度お試しください。";
    }
    // 返信自体の失敗は捕捉して再送しない。同じ返信トークンでの二重送信を避ける。
    this.line.reply(replyToken, msg);
  }
}
