package app.presentation.mapper;

import java.util.*;
import java.util.stream.Stream;

import app.util.IResult;

import app.presentation.mapper.ParsedCommand.ICommandElement;
import app.presentation.mapper.ParsedCommand.ICommandElement.*;


/**
 * コマンド要素を読み取り、無名要素の消費位置を管理する。
 * 名前付き要素は消費せず、繰り返し読み取れる。
 * 未指定は Ok(Optional.empty())、入力エラーは Err(message) を返す。
 */
public class CommandElementReader {

  private final Map<String, List<ICommandElement>> named = new LinkedHashMap<>();
  private final List<ICommandElement> unnamed = new ArrayList<>();
  private int position = 0;

  public CommandElementReader(ParsedCommand command)
  {
    for(var element : command.elements()) {

      // 名前付き要素は名前で分類し、無名要素は順序を保持する
      String name = switch(element) {
        case ParamElement e -> e.param();
        case FlatListElement e -> e.param();
        case ListElement e -> e.param();
        case SwitchElement e -> e.param();
        default -> null;
      };

      if(name == null) {
        this.unnamed.add(element);
        continue;
      }
      this.named.computeIfAbsent(name, key -> new ArrayList<>()).add(element);
    }
  }


  // ###########################################################################
  // MARK: 読み取り

  /**
   * 単一値を読み取る。同名要素の重複や名前付き要素の型不一致はエラー。
   * 無名の先頭が単一値でなければ、消費せずに空を返す。
   */
  public IResult<Optional<String>, String> readStr(String name)
  {
    // 名前付き要素があれば、単一値を返す
    var elements = this.named.get(name);
    if(elements != null) {
      if(elements.size() > 1) return IResult.err(this.duplicate(name));
      if(elements.get(0) instanceof ParamElement e) return IResult.ok(Optional.of(e.value()));
      return IResult.err(this.typeMismatch(name, "単一値"));
    }

    // 無名要素が存在しなければ空を返す
    if(this.position >= this.unnamed.size()) return IResult.ok(Optional.empty());
    
    // 無名要素の先頭が単一値でなければ空を返す
    var element = this.unnamed.get(this.position);
    Optional<String> value = switch(element) {
      case ValueElement e -> Optional.of(e.value());
      case QuasiUnnamedParamElement e -> Optional.of(e.value());
      default -> Optional.empty();
    };
    if(value.isPresent()) this.position++;
    return IResult.ok(value);
  }

  /**
   * 単一値を列挙定数に変換する。定数名は大文字・小文字を区別しない。
   * 未指定なら成功の空値、変換できなければ失敗。読み取った無名値は変換失敗時も消費される。
   */
  public <E extends Enum<E>> IResult<Optional<E>, String> readEnum(String name, Class<E> clazz)
  {
    Objects.requireNonNull(clazz);
    return this.readStr(name)
      .flatMap(value -> {
        if(value.isEmpty()) return IResult.ok(Optional.empty());
        for(var constant : clazz.getEnumConstants()) {
          if(constant.name().equalsIgnoreCase(value.get())) return IResult.ok(Optional.of(constant));
        }
        var typeNames = Stream.of(clazz.getEnumConstants())
          .map(v -> v.name())
          .toList();
        return IResult.err("列挙定数に変換できません: " + name + " = " + value.get()
          + "（期待: " + typeNames + "）");
      });
  }

  /**
   * 単一値を10進整数に変換する。未指定なら成功の空値、変換不能・範囲外なら失敗。
   * 読み取った無名値は変換失敗時も消費される。
   */
  public IResult<Optional<Integer>, String> readInt(String name)
  {
    return this.readStr(name)
      .flatMap(value -> {
        if(value.isEmpty()) return IResult.ok(Optional.empty());
        try {
          return IResult.ok(Optional.of(Integer.parseInt(value.get())));
        } catch(NumberFormatException e) {
          return IResult.err("整数に変換できません: " + name + " = " + value.get());
        }
      });
  }

  /**
   * 同名の単一値・リストを登場順に結合する。スイッチが混在するとエラー。
   * 名前付き要素がなければ、無名の先頭1要素だけをリストとして消費する。
   */
  public IResult<Optional<List<String>>, String> readStrList(String name)
  {
    // 名前付き要素があれば、単一値・リストを結合して返す
    var elements = this.named.get(name);
    if(elements != null) {
      var values = new ArrayList<String>();
      for(var element : elements) {
        switch(element) {
          case ParamElement e -> values.add(e.value());
          case FlatListElement e -> values.addAll(e.values());
          case ListElement e -> values.addAll(e.values());
          default -> { return IResult.err(this.typeMismatch(name, "単一値またはリスト")); }
        }
      }
      return IResult.ok(Optional.of(List.copyOf(values)));
    }

    // 無名要素が存在しなければ空を返す
    if(this.position >= this.unnamed.size()) return IResult.ok(Optional.empty());

    // 無名要素の先頭が単一値・リストでなければ空を返す
    var element = this.unnamed.get(this.position);
    Optional<List<String>> values = switch(element) {
      case ValueElement e -> Optional.of(List.of(e.value()));
      case QuasiUnnamedParamElement e -> Optional.of(List.of(e.value()));
      case UnnamedFlatListElement e -> Optional.of(e.values());
      case QuasiUnnamedFlatListElement e -> Optional.of(e.values());
      case UnnamedListElement e -> Optional.of(e.values());
      case QuasiUnnamedListElement e -> Optional.of(e.values());
      default -> Optional.empty();
    };
    if(values.isPresent()) this.position++;
    return IResult.ok(values);
  }

  /**
   * スイッチがあれば true、未指定なら空を返す。無名要素は消費しない。
   * 同名要素の重複や型不一致はエラー。
   */
  public IResult<Optional<Boolean>, String> readSwitch(String name)
  {
    // 名前付き要素があれば、スイッチの有無を返す
    var elements = this.named.get(name);
    if(elements == null) return IResult.ok(Optional.empty());
    
    if(elements.size() > 1) return IResult.err(this.duplicate(name));
    if(elements.get(0) instanceof SwitchElement) return IResult.ok(Optional.of(true));
    
    return IResult.err(this.typeMismatch(name, "スイッチ"));
  }


  // ###########################################################################
  // MARK: 検証

  private String duplicate(String name)
  {
    return "パラメータが重複しています: " + name;
  }

  private String typeMismatch(String name, String expected)
  {
    return "パラメータの型が一致しません: " + name + "（期待: " + expected + "）";
  }
}
