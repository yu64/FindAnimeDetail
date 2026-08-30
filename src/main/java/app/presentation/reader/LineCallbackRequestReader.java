package app.presentation.reader;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

import com.linecorp.bot.model.event.CallbackRequest;
import com.linecorp.bot.parser.LineSignatureValidator;
import com.linecorp.bot.parser.WebhookParseException;
import com.linecorp.bot.parser.WebhookParser;

import app.config.LineConfig;
import io.quarkus.logging.Log;

@Provider
@Consumes(MediaType.APPLICATION_JSON)
public class LineCallbackRequestReader implements MessageBodyReader<CallbackRequest> {

  private WebhookParser parser;

  @Inject 
  public LineCallbackRequestReader(
    LineConfig lineConfig
  )
  {
    final var validator = new LineSignatureValidator(
      lineConfig.channelSecret().getBytes()
    );

    // application.properties から受け取ったSecretでパーサーを初期化
    this.parser = new WebhookParser(validator);
  }


  @Override
  public boolean isReadable(
    Class<?> type,
    Type genericType,
    Annotation[] annotations,
    MediaType mediaType
  )
  {
    return (type == CallbackRequest.class);
  }

  @Override
  public CallbackRequest readFrom(
    Class<CallbackRequest> type,
    Type genericType,
    Annotation[] annotations,
    MediaType mediaType,
    MultivaluedMap<String,
    String> httpHeaders,
    InputStream entityStream
  ) throws IOException, WebApplicationException
  {
    Log.info("Catch Request");

    String signature = httpHeaders.getFirst("x-line-signature");
    if(signature == null)
    {
      throw new WebApplicationException(Response.Status.UNAUTHORIZED);
    }

    try
    {
      byte[] bodyBytes = entityStream.readAllBytes();
      return this.parser.handle(signature, bodyBytes);
    }
    catch(WebhookParseException ex)
    {
      Log.error("Failed Signature.", ex);
      // 署名検証に失敗した場合は 401 Unauthorized を返す
      throw new WebApplicationException(Response.Status.UNAUTHORIZED);
    }
    catch(Exception ex)
    {
      Log.error("Failed Auth.", ex);
      // その他の例外の場合、400 を返す。
      throw new WebApplicationException(Response.Status.BAD_REQUEST);
    }
  }
}
