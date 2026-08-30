package app.usecase.dto;

import java.util.*;

/**
 * パーサーが返す汎用コマンドDTO
 * パース順序を保持し、情報を落とさない
 */
public record ParsedCommand(String command, List<ICommandElement> elements) {

  // コンパクト・コンストラクタでListを不変にする防衛的コピー
  public ParsedCommand {
    elements = Collections.unmodifiableList(new ArrayList<>(elements));
  }

  /**
   * シールドされたコマンド要素の基底インターフェイス
   */
  public sealed interface ICommandElement {

    /**
     * パラメータ構文: #param value
     */
    record ParamElement(String param, String value) implements ICommandElement {
    }

    /**
     * フラットリスト構文: #param val1, val2, ...
     */
    record FlatListElement(String param, List<String> values) implements ICommandElement {
      public FlatListElement {
        values = Collections.unmodifiableList(new ArrayList<>(values));
      }
    }

    /**
     * スイッチ構文: #param
     */
    record SwitchElement(String param) implements ICommandElement {
    }

    /**
     * 無名値: value （単独）
     */
    record ValueElement(String value) implements ICommandElement {
    }

    /**
     * リスト構文: 
     * #param
     * val1
     * val2
     * ...
     */
    record ListElement(String param, List<String> values) implements ICommandElement {
      public ListElement {
        values = Collections.unmodifiableList(new ArrayList<>(values));
      }
    }

    /**
     * 準無名パラメータ構文: # value
     */
    record QuasiUnnamedParamElement(String value) implements ICommandElement {
    }

    /**
     * 無名フラットリスト構文: val1, val2, ...
     */
    record UnnamedFlatListElement(List<String> values) implements ICommandElement {
      public UnnamedFlatListElement {
        values = Collections.unmodifiableList(new ArrayList<>(values));
      }
    }

    /**
     * 準無名フラットリスト構文: # val1, val2, ...
     */
    record QuasiUnnamedFlatListElement(List<String> values) implements ICommandElement {
      public QuasiUnnamedFlatListElement {
        values = Collections.unmodifiableList(new ArrayList<>(values));
      }
    }

    /**
     * 無名リスト構文:
     * val1
     * val2
     * ...
     */
    record UnnamedListElement(List<String> values) implements ICommandElement {
      public UnnamedListElement {
        values = Collections.unmodifiableList(new ArrayList<>(values));
      }
    }

    /**
     * 準無名リスト構文:
     * #
     * val1
     * val2
     * ...
     */
    record QuasiUnnamedListElement(List<String> values) implements ICommandElement {
      public QuasiUnnamedListElement {
        values = Collections.unmodifiableList(new ArrayList<>(values));
      }
    }
  }

  /**
   * 特定パラメータの最初の値を取得
   */
  public String getParamValue(String paramName) {
    for (ICommandElement elem : elements) {
      if (elem instanceof ICommandElement.ParamElement p && p.param().equals(paramName)) {
        return p.value();
      }
    }
    return null;
  }

  /**
   * 特定パラメータのフラットリスト値を取得
   */
  public List<String> getFlatListValues(String paramName) {
    for (ICommandElement elem : elements) {
      if (elem instanceof ICommandElement.FlatListElement fl && fl.param().equals(paramName)) {
        return fl.values();
      }
    }
    return null;
  }

  /**
   * 特定パラメータのリスト値を取得
   */
  public List<String> getListValues(String paramName) {
    for (ICommandElement elem : elements) {
      if (elem instanceof ICommandElement.ListElement l && l.param().equals(paramName)) {
        return l.values();
      }
    }
    return null;
  }

  /**
   * スイッチが有効か確認
   */
  public boolean hasSwitch(String switchName) {
    for (ICommandElement elem : elements) {
      if (elem instanceof ICommandElement.SwitchElement s && s.param().equals(switchName)) {
        return true;
      }
    }
    return false;
  }

  /**
   * すべての無名値を取得（出現順）
   */
  public List<String> getAllValues() {
    List<String> result = new ArrayList<>();
    for (ICommandElement elem : elements) {
      if (elem instanceof ICommandElement.ValueElement v) {
        result.add(v.value());
      }
    }
    return result;
  }

  /**
   * 準無名パラメータの値を取得（最初の出現）
   */
  public String getQuasiUnnamedParamValue() {
    for (ICommandElement elem : elements) {
      if (elem instanceof ICommandElement.QuasiUnnamedParamElement qu) {
        return qu.value();
      }
    }
    return null;
  }

  /**
   * 無名フラットリストの値を取得（最初の出現）
   */
  public List<String> getUnnamedFlatListValues() {
    for (ICommandElement elem : elements) {
      if (elem instanceof ICommandElement.UnnamedFlatListElement ufl) {
        return ufl.values();
      }
    }
    return null;
  }

  /**
   * 準無名フラットリストの値を取得（最初の出現）
   */
  public List<String> getQuasiUnnamedFlatListValues() {
    for (ICommandElement elem : elements) {
      if (elem instanceof ICommandElement.QuasiUnnamedFlatListElement qufl) {
        return qufl.values();
      }
    }
    return null;
  }

  /**
   * 無名リストの値を取得（最初の出現）
   */
  public List<String> getUnnamedListValues() {
    for (ICommandElement elem : elements) {
      if (elem instanceof ICommandElement.UnnamedListElement ul) {
        return ul.values();
      }
    }
    return null;
  }

  /**
   * 準無名リストの値を取得（最初の出現）
   */
  public List<String> getQuasiUnnamedListValues() {
    for (ICommandElement elem : elements) {
      if (elem instanceof ICommandElement.QuasiUnnamedListElement qul) {
        return qul.values();
      }
    }
    return null;
  }

}