package app.usecase;

/** EDCBとの通信または応答の解釈に失敗したことを表す。presentation層から参照できるよう usecase 層に置く。 */
public class EdcbException extends RuntimeException {
  public EdcbException(String message) {
    super(message);
  }

  public EdcbException(String message, Throwable cause) {
    super(message, cause);
  }
}
