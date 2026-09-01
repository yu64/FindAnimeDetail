package app.infrastructure;

/** アニメ情報APIとの通信または応答の解釈に失敗したことを表す。 */
public class AnimeSearchException extends RuntimeException {
  public AnimeSearchException(String message) {
    super(message);
  }

  public AnimeSearchException(String message, Throwable cause) {
    super(message, cause);
  }
}
