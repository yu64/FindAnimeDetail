package app.usecase;

import static org.junit.jupiter.api.Assertions.*;

import app.config.EdcbConfig;
import app.usecase.IInput.ReserveInput;
import app.usecase.IInput.ReserveInput.Entry;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReserveUsecaseTest {
  private static final String HEADER = "結果\tタイトル\tキーワード\tEPGヒット\t備考";

  private final EdcbConfig config = new EdcbConfig() {
    public URI url() {
      return URI.create("https://example.com");
    }
    public String headers() {
      return "{}";
    }
    public String recFolder() {
      return "/record/";
    }
  };

  /** 登録内容を記録する。failOn に含むキーワードの登録、または listFails で一覧の取得を失敗させる。 */
  static class Stub implements IEdcbClient {
    final List<String> existing = new ArrayList<>();
    final Map<String, Integer> hits = new HashMap<>();
    final List<String> added = new ArrayList<>();
    final List<String> failOn = new ArrayList<>();
    boolean listFails;

    public List<String> autoAddKeywords() {
      if(listFails) throw new EdcbException("connection refused");
      return existing;
    }

    public int countEvents(String keyword) {
      return hits.getOrDefault(keyword, 0);
    }

    public void addAutoAdd(String keyword, String recFolder) {
      if(failOn.contains(keyword)) throw new EdcbException("rejected");
      added.add(keyword + " -> " + recFolder);
    }
  }

  private String run(Stub stub, Entry... entries) {
    return new ReserveUsecase(stub, config).run(new ReserveInput(List.of(entries)));
  }

  // ###########################################################################
  // MARK: 登録

  @Test
  void registersEachTitleWithHitCountAndFolderPerTitle() {
    var stub = new Stub();
    stub.hits.put("SHIROBAKO", 3);

    assertEquals(String.join("\n", HEADER,
        "登録\tSHIROBAKO\tSHIROBAKO\t3\t",
        "登録\t葬送のフリーレン 第2期\t葬送のフリーレン\t0\tEPGに一致する番組はまだありません"),
        run(stub, Entry.ok("SHIROBAKO"), Entry.ok("葬送のフリーレン 第2期")));
    // 保存先はキーワードではなくタイトルで分け、親フォルダ末尾の '/' は重ねない。
    assertEquals(List.of("SHIROBAKO -> /record/SHIROBAKO", "葬送のフリーレン -> /record/葬送のフリーレン 第2期"),
        stub.added);
  }

  @Test
  void skipsKeywordsAlreadyRegisteredOrRepeatedInInput() {
    var stub = new Stub();
    stub.existing.add("ＳＨＩＲＯＢＡＫＯ");

    assertEquals(String.join("\n", HEADER,
        "重複\tshirobako\tshirobako\t-\t同じキーワードの自動予約があります",
        "登録\t鬼滅の刃\t鬼滅の刃\t0\tEPGに一致する番組はまだありません",
        "重複\t鬼滅の刃 Season 2\t鬼滅の刃\t-\t同じキーワードの自動予約があります"),
        run(stub, Entry.ok("shirobako"), Entry.ok("鬼滅の刃"), Entry.ok("鬼滅の刃 Season 2")));
    assertEquals(1, stub.added.size());
  }

  @Test
  void keepsInputErrorsAsFailedRows() {
    assertEquals(String.join("\n", HEADER,
        "失敗\t2026-10-03 01:30 土\t\t-\tタイトルがありません"),
        run(new Stub(), Entry.err("2026-10-03 01:30 土", "タイトルがありません")));
  }

  // ###########################################################################
  // MARK: EDCBの異常

  @Test
  void failsEveryRowWhenEdcbIsUnreachable() {
    var stub = new Stub();
    stub.listFails = true;

    assertEquals(String.join("\n", HEADER,
        "失敗\tSHIROBAKO\tSHIROBAKO\t-\tEDCBに接続できません",
        "失敗\t2026-10-03\t\t-\tタイトルがありません"),
        run(stub, Entry.ok("SHIROBAKO"), Entry.err("2026-10-03", "タイトルがありません")));
  }

  @Test
  void abortsRemainingRowsAfterFailureWithoutExposingDetails() {
    var stub = new Stub();
    stub.failOn.add("SHIROBAKO");

    var result = run(stub, Entry.ok("SHIROBAKO"), Entry.ok("鬼滅の刃"));
    assertEquals(String.join("\n", HEADER,
        "失敗\tSHIROBAKO\tSHIROBAKO\t-\tEDCBへの登録に失敗しました",
        "失敗\t鬼滅の刃\t鬼滅の刃\t-\t前の行でEDCBへの登録に失敗したため中止しました"), result);
    assertFalse(result.contains("rejected"));
    assertEquals(List.of(), stub.added);
  }

  // ###########################################################################
  // MARK: キーワード・保存先

  @Test
  void keywordRemovesTrailingSeasonNotation() {
    var cases = Map.of(
        "Re:ゼロから始める異世界生活 4th Season", "Re:ゼロから始める異世界生活",
        "葬送のフリーレン 第2期", "葬送のフリーレン",
        "薬屋のひとりごと（第2クール）", "薬屋のひとりごと",
        "進撃の巨人 The Final Season", "進撃の巨人",
        "SPY×FAMILY Season 3", "SPY×FAMILY",
        "ダンジョン飯 2期", "ダンジョン飯",
        "【推しの子】 第３期", "【推しの子】",
        "【推しの子】", "【推しの子】",
        "第2期", "第2期",
        "転生したら第七王子だったので、気ままに魔術を極めます", "転生したら第七王子だったので、気ままに魔術を極めます");
    cases.forEach((title, keyword) -> assertEquals(keyword, ReserveUsecase.keyword(title), title));
  }

  @Test
  void folderReplacesCharactersInvalidForFileNames() {
    var stub = new Stub();
    run(stub, Entry.ok("Re:ゼロ <A/B>?."), Entry.ok("..."));
    assertEquals(List.of("Re:ゼロ <A/B>?. -> /record/Re_ゼロ _A_B__", "... -> /record/_"), stub.added);
  }
}
