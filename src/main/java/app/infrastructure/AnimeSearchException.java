package app.infrastructure;

import jakarta.ws.rs.WebApplicationException;

/** アニメ情報APIとの通信または応答の解釈に失敗したことを表す。 */
public class AnimeSearchException extends RuntimeException {
  public AnimeSearchException(String message) {
    super(message);
  }

  public AnimeSearchException(String message, Throwable cause) {
    super(message, cause);
  }

  /** REST Clientがラップした例外も含め、HTTP 429を判定する。 */
  public boolean isRateLimited() {
    for (Throwable cause = this; cause != null; cause = cause.getCause()) {
      if (cause instanceof WebApplicationException http
          && http.getResponse() != null && http.getResponse().getStatus() == 429) {
        return true;
      }
    }
    return false;
  }
}
