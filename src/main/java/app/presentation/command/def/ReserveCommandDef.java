package app.presentation.command.def;

import app.presentation.command.parse.CommandExpression;
import app.presentation.command.parse.CommandExpression.IElement.UnnamedListElement;
import app.usecase.IInput;
import app.usecase.IInput.ReserveInput;
import app.usecase.IInput.ReserveInput.Entry;
import app.util.IResult;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@ApplicationScoped
public class ReserveCommandDef implements ICommandDef {

  /** /find の TSV でタイトルを持つ列の見出し。 */
  private static final String TITLE_COLUMN = "タイトル";

  @Override
  public String name() {
    return "reserve";
  }

  @Override
  public String help() {
    return """
      [/reserve]
      作品をEDCBのEPG自動予約に登録します。

      入力例（/find の結果を貼り付け、予約しない行を消す）:
      /reserve
      放送開始日\t時刻\t曜日\t放送局\tタイトル\t公式URL
      2026-10-05\t23:00\t日\tTOKYO MX\tぼく\thttps://example.com

      入力例（作品名を1行ずつ）:
      /reserve
      ぼく
      わたし

      - 先頭行に「タイトル」列があれば、その列の作品名を使います。ほかの列は使いません。
      - 見出しがなければ、1行を1作品として扱います。
      - #で始まる作品名は "#作品名" のように " で囲んでください。

      結果はTSVで返します。
      結果\tタイトル\tキーワード\tEPGヒット\t備考
      """;
  }

  @Override
  public IResult<IInput, String> map(CommandExpression cmd) {
    // 作品は2行目以降の無名リストだけで受け取る。空行で分かれた場合も1つの表として続ける。
    List<String> lines = new ArrayList<>();
    for(var element : cmd.elements()) {
      if(!(element instanceof UnnamedListElement list)) {
        return IResult.err("予約する作品は2行目以降に1行ずつ入力してください");
      }
      list.values().stream().filter(line -> !line.isBlank()).forEach(lines::add);
    }

    // 先頭行に「タイトル」列があれば見出しとし、その列だけを使う。
    int titleColumn = lines.isEmpty() ? -1 : cells(lines.getFirst()).indexOf(TITLE_COLUMN);
    var rows = titleColumn < 0 ? lines : lines.subList(1, lines.size());

    if(rows.isEmpty()) return IResult.err("予約する作品を2行目以降に入力してください");

    // 形式が不正な行も結果に残すため、コマンド全体は失敗にせず行ごとにエラーを持たせる。
    List<Entry> entries = new ArrayList<>();
    for(String row : rows) {
      entries.add(titleColumn < 0 ? titleOnly(row) : fromTsv(row, titleColumn));
    }

    return IResult.ok(new ReserveInput(entries));
  }

  /** 見出しのない行は、行全体を作品名とする。行頭のタブはパーサーが残すため、前後の空白を除いてから判定する。 */
  private static Entry titleOnly(String row) {
    var title = row.strip();
    if(title.indexOf('\t') >= 0) {
      return Entry.err(display(row), "TSVの先頭行に「" + TITLE_COLUMN + "」列の見出しが必要です");
    }
    return Entry.ok(title);
  }

  /** TSV の行から、見出しで指定された列の作品名を取り出す。 */
  private static Entry fromTsv(String row, int titleColumn) {
    var cells = cells(row);
    if(titleColumn >= cells.size() || cells.get(titleColumn).isBlank()) {
      return Entry.err(display(row), "タイトルがありません");
    }
    return Entry.ok(cells.get(titleColumn).strip());
  }

  /** 末尾の空欄も列として残す。 */
  private static List<String> cells(String row) {
    return Arrays.stream(row.split("\t", -1)).map(String::strip).toList();
  }

  /** 結果のTSVに載せるため、タブを空白にする。 */
  private static String display(String row) {
    return row.replace('\t', ' ').strip();
  }
}
