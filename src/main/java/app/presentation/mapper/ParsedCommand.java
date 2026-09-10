package app.presentation.mapper;

import java.util.*;


/**
 * パーサーが返す汎用コマンドのデータ構造
 */
public record ParsedCommand(
  String command,
  List<ICommandElement> elements
) {

  public ParsedCommand
  {
    elements = Collections.unmodifiableList(new ArrayList<>(elements));
  }


  // ###########################################################################
  // MARK: 型定義

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

}
