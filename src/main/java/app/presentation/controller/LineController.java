package app.presentation.controller;

import app.config.LineConfig;
import app.infrastructure.AnimeSearchException;
import app.presentation.ILineClient;
import app.presentation.command.def.CommandDefRegistry;
import app.presentation.command.parse.CommandExpression;
import app.presentation.command.parse.ICommandSyntaxParser;
import app.usecase.FindUsecase;
import app.usecase.IInput;
import app.usecase.IInput.FindInput;
import app.usecase.IInput.HelpInput;
import app.usecase.IInput.ReserveInput;
import app.usecase.ReserveUsecase;
import app.usecase.help.HelpUsecase;
import app.util.IResult;
import com.linecorp.bot.model.event.CallbackRequest;
import com.linecorp.bot.model.event.MessageEvent;
import com.linecorp.bot.model.event.message.TextMessageContent;
import io.quarkus.logging.Log;
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
  private final LineConfig config;
  private final ICommandSyntaxParser parser;
  private final CommandDefRegistry registry;
  private final HelpUsecase helpUsecase;
  private final FindUsecase findUsecase;
  private final ReserveUsecase reserveUsecase;

  @Inject
  public LineController(
      ILineClient line,
      LineConfig config,
      ICommandSyntaxParser parser,
      CommandDefRegistry registry,
      HelpUsecase helpUsecase,
      FindUsecase findUsecase,
      ReserveUsecase reserveUsecase) {
    this.line = line;
    this.config = config;
    this.parser = parser;
    this.registry = registry;
    this.helpUsecase = helpUsecase;
    this.findUsecase = findUsecase;
    this.reserveUsecase = reserveUsecase;
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Path("/webhook")
  public Response handle(CallbackRequest req) {
    if(req == null || req.getEvents() == null) {
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
  private void onMessage(MessageEvent<?> messageEvent) {
    if(!(messageEvent.getMessage() instanceof TextMessageContent textMessageContent)) {
      return;
    }

    // LINEから入力文字列と送信トークンを取得
    String userText = textMessageContent.getText();
    String replyToken = messageEvent.getReplyToken();

    // 許可されたユーザー以外には、コマンドを解釈せず権限がない旨だけを返す。
    // 拒否したuserIdはログに残し、許可リストへの登録に使えるようにする。
    String userId = messageEvent.getSource() == null ? null : messageEvent.getSource().getUserId();
    if(!this.isAllowed(userId)) {
      Log.warnf("Rejected message from unauthorized user: %s", userId);
      this.line.reply(replyToken, "このボットを利用する権限がありません。");
      return;
    }

    // コマンドパース
    IResult<CommandExpression, String> parseResult = this.parser.parse(userText);
    if(parseResult instanceof IResult.Err(var error)) {
      var msg = ("コマンドのパースに失敗しました。\n"
          + "ヘルプが必要な場合は `/help` を実行してください。\n"
          + "\n"
          + error);
      this.line.reply(replyToken, msg);
      return;
    }

    // コマンドパースに成功したら、実在するコマンドにマッピング
    IResult<IInput, String> cmdResult = this.registry.map(parseResult.ok().val());
    if(cmdResult instanceof IResult.Err(var error)) {
      var msg = ("コマンドのパースに失敗しました。\n"
          + "ヘルプが必要な場合は `/help` を実行してください。\n"
          + "\n"
          + error);
      this.line.reply(replyToken, msg);
      return;
    }

    // ユースケースに振り分けて実行
    IInput cmd = cmdResult.ok().val();
    String msg;
    try {
      msg = switch(cmd) {
        case HelpInput c -> this.helpUsecase.run(c);
        case FindInput c -> this.findUsecase.run(c);
        case ReserveInput c -> this.reserveUsecase.run(c);
      };
    } catch(AnimeSearchException e) {
      Log.error("Anime search failed", e);
      msg = e.isRateLimited()
          ? "Annictのアクセス制限により検索できませんでした。しばらく時間を置いてから再検索してください。"
          : "アニメ情報の取得に失敗しました。しばらく時間を置いてから再検索してください。";
    } catch(RuntimeException e) {
      Log.error("Command execution failed", e);
      msg = "処理中にエラーが発生しました。しばらく時間を置いてから再度お試しください。";
    }
    // 返信自体の失敗は捕捉して再送しない。同じ返信トークンでの二重送信を避ける。
    this.line.reply(replyToken, msg);
  }

  /** 許可リストが未設定の場合は、誰も利用できない。 */
  private boolean isAllowed(String userId) {
    return userId != null && this.config.allowedUserIds().stream()
        .map(String::strip)
        .anyMatch(userId::equals);
  }
}
