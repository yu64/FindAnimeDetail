package app.usecase;

import app.usecase.IInput.ReserveInput;
import app.usecase.IInput.ReserveInput.Entry;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.StringJoiner;

/** 作品をEDCBのEPG自動予約に登録する。現在は受付のみで、EDCBへの登録は未実装。 */
@ApplicationScoped
public class ReserveUsecase {

  /** 入力の行ごとに結果を1行ずつ、TSVで返す。 */
  public String run(ReserveInput input) {
    var tsv = new StringJoiner("\n");
    tsv.add("結果\tタイトル\tキーワード\tEPGヒット\t備考");

    for(Entry entry : input.entries()) {
      tsv.add(entry.error() == null
          ? String.join("\t", "受付", entry.title(), entry.title(), "-", "予約処理は準備中です")
          : String.join("\t", "失敗", entry.title(), "", "-", entry.error()));
    }

    return tsv.toString();
  }
}
