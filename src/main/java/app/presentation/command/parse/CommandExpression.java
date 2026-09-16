package app.presentation.command.parse;

import java.util.*;


/**
 * パーサーが返す汎用コマンドのデータ構造
 */
public record CommandExpression(
  String command,
  List<IElement> elements
) {

  public CommandExpression
  {
    elements = Collections.unmodifiableList(new ArrayList<>(elements));
  }


  // ###########################################################################
  // MARK: 型定義

  /**
   * シールドされたコマンド要素の基底インターフェイス
   */
  public sealed interface IElement {

    /**
     * パラメータ構文: #param value
     */
    record ParamElement(String param, String value) implements IElement {
    }

    /**
     * フラットリスト構文: #param val1, val2, ...
     */
    record FlatListElement(String param, List<String> values) implements IElement {
      public FlatListElement {
        values = Collections.unmodifiableList(new ArrayList<>(values));
      }
    }

    /**
     * スイッチ構文: #param
     */
    record SwitchElement(String param) implements IElement {
    }

    /**
     * 無名値: value （単独）
     */
    record ValueElement(String value) implements IElement {
    }

    /**
     * リスト構文: 
     * #param
     * val1
     * val2
     * ...
     */
    record ListElement(String param, List<String> values) implements IElement {
      public ListElement {
        values = Collections.unmodifiableList(new ArrayList<>(values));
      }
    }

    /**
     * 準無名パラメータ構文: # value
     */
    record QuasiUnnamedParamElement(String value) implements IElement {
    }

    /**
     * 無名フラットリスト構文: val1, val2, ...
     */
    record UnnamedFlatListElement(List<String> values) implements IElement {
      public UnnamedFlatListElement {
        values = Collections.unmodifiableList(new ArrayList<>(values));
      }
    }

    /**
     * 準無名フラットリスト構文: # val1, val2, ...
     */
    record QuasiUnnamedFlatListElement(List<String> values) implements IElement {
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
    record UnnamedListElement(List<String> values) implements IElement {
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
    record QuasiUnnamedListElement(List<String> values) implements IElement {
      public QuasiUnnamedListElement {
        values = Collections.unmodifiableList(new ArrayList<>(values));
      }
    }
  }

}
