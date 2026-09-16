package app.infrastructure.client;

import java.util.List;

import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import app.presentation.ILineClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;

/**
 * QuarkusのRestClient自動生成によって作成される
 * https://ja.quarkus.io/guides/rest-client/
 */
@RegisterRestClient(configKey = "line-api-base")
@ClientHeaderParam(name = "Authorization", value = "Bearer ${line-bot-channel-token}")
public interface ILineClientDef extends ILineClient {
  

  /** LINEで相手にテキストで応答する。 */
  public default void reply(String replyToken, String text)
  {
    ReplyPayload payload = new ReplyPayload(
        replyToken,
        List.of(new LineMessage("text", text))
    );
    this.reply(payload);
  }

  /** LINEで相手に応答する */
  @POST
  @Path("/v2/bot/message/reply")
  @Consumes(MediaType.APPLICATION_JSON)
  public void reply(ReplyPayload body);


  /** インターフェイスを明示して再登録する。*/
  @ApplicationScoped
  public class DiRelay
  {
    @Produces
    @ApplicationScoped
    public ILineClient relay(@RestClient ILineClientDef v)
    {
        return v;
    }
  }
}
