package app.presentation.filter;

import io.quarkus.logging.Log;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;


@Provider
public class LoggingFilter implements ContainerRequestFilter {

  @Override
  public void filter(ContainerRequestContext requestContext) throws IOException {
    // メソッド名とリクエストパスをログ出力
    String method = requestContext.getMethod();
    String path = requestContext.getUriInfo().getPath();

    Log.infof("Receive HTTP Request: [%s] %s", method, path);
  }
}
