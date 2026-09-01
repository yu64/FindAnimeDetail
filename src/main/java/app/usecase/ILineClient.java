package app.usecase;

import java.util.List;

public interface ILineClient 
{
  public static record LineMessage(String type, String text) {}
  public static record ReplyPayload(String replyToken, List<LineMessage> messages) {}

  public void reply(String replyToken, String text);
}
