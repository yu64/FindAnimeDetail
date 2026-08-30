package app.presentation.filter;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import io.quarkus.logging.Log;
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
