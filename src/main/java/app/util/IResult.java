package app.util;

import java.util.Objects;
import java.util.function.Function;

/**
 * 成功値またはエラーを表す結果型。値とエラーに null は許可しない。
 * コールバックが投げた例外は Err に変換せず、そのまま呼び出し元へ伝播する。
 *
 * @param <T> 成功値の型
 * @param <E> エラーの型（Throwable に限らない）
 */
public sealed interface IResult<T, E> {


  // ###########################################################################
  // MARK: 型定義


  public record Ok<T, E>(T val) implements IResult<T, E> {
    public Ok {
      Objects.requireNonNull(val, "val");
    }
  }

  public record Err<T, E>(E val) implements IResult<T, E> {
    public Err {
      Objects.requireNonNull(val, "val");
    }
  }


  // ###########################################################################
  // MARK: ファクトリ


  public static <T, E> IResult<T, E> ok(T value) {
    return new Ok<>(value);
  }

  public static <T, E> IResult<T, E> err(E value) {
    return new Err<>(value);
  }


  // ###########################################################################
  // MARK: 変換


  /** 成功値だけを変換する。 */
  public default <U> IResult<U, E> map(Function<? super T, ? extends U> mapper) {
    Objects.requireNonNull(mapper, "mapper");
    return switch (this) {
      case Ok(var value) -> IResult.ok(mapper.apply(value));
      case Err(var error) -> IResult.err(error);
    };
  }

  /** 成功時だけ次の処理を実行する。 */
  public default <U> IResult<U, E> flatMap(
    Function<? super T, ? extends IResult<U, E>> mapper
  ) {
    Objects.requireNonNull(mapper, "mapper");
    return switch (this) {
      case Ok(var value) -> Objects.requireNonNull(mapper.apply(value), "result");
      case Err(var error) -> IResult.err(error);
    };
  }

  /** エラーだけを変換する。 */
  public default <F> IResult<T, F> mapErr(Function<? super E, ? extends F> mapper) {
    Objects.requireNonNull(mapper, "mapper");
    return switch (this) {
      case Ok(var value) -> IResult.ok(value);
      case Err(var error) -> IResult.err(mapper.apply(error));
    };
  }

  /** 成功・失敗それぞれの処理を指定し、共通の戻り値に変換する。 */
  public default <U> U fold(
    Function<? super T, ? extends U> onSuccess,
    Function<? super E, ? extends U> onFailure
  ) {
    Objects.requireNonNull(onSuccess, "onSuccess");
    Objects.requireNonNull(onFailure, "onFailure");
    return switch (this) {
      case Ok(var value) -> onSuccess.apply(value);
      case Err(var error) -> onFailure.apply(error);
    };
  }


  
  // ###########################################################################
  // MARK: キャスト

  /** 成功なら同じインスタンスを返し、失敗なら ClassCastException を投げる。 */
  @SuppressWarnings("unchecked")
  public default <U> Ok<T, U> ok() {

    // Ok はエラー値を保持しないため、成功型 T を保ったままエラー型だけ変更できる。
    if(this instanceof Ok<T, E> ok) {
      return (Ok<T, U>) ok;
    }
    throw new ClassCastException("Err cannot be cast to Ok");
  }

  /** 失敗なら同じインスタンスを返し、成功なら ClassCastException を投げる。 */
  @SuppressWarnings("unchecked")
  public default <U> Err<U, E> err() {

    // Err は成功値を保持しないため、エラー型 E を保ったまま成功型だけ変更できる。
    if(this instanceof Err<T, E> err) {
      return (Err<U, E>) err;
    }
    throw new ClassCastException("Ok cannot be cast to Err");
  }




}
