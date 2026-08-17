package app.resource;

import com.linecorp.bot.model.event.CallbackRequest;
import com.linecorp.bot.model.event.Event;
import com.linecorp.bot.model.event.MessageEvent;
import com.linecorp.bot.model.event.message.TextMessageContent;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/line")
public class LineResource {

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Path("/webhook")
  public Response handle(CallbackRequest req)
  {
    // 1. CallbackRequest からイベントのリストを取り出す
    for (Event event : req.getEvents()) {

        // 2. イベントが「メッセージ受信イベント」かどうかをチェック
        if (event instanceof MessageEvent) {
            MessageEvent<?> messageEvent = (MessageEvent<?>) event;

            // 3. メッセージの中身が「テキスト（文字列）」かどうかをチェック
            if (messageEvent.getMessage() instanceof TextMessageContent) {
                TextMessageContent textContent = (TextMessageContent) messageEvent.getMessage();

                // 4. ここでついに、実際のテキストと返信用のトークンが手に入ります！
                String userText = textContent.getText(); // ユーザーが送ってきた文字
                String replyToken = messageEvent.getReplyToken(); // 返信用トークン

                System.out.println("届いたメッセージ: " + userText);
                System.out.println("返信用のトークン: " + replyToken);
            }
        }
    }

    return Response.ok().build();
  }
}
