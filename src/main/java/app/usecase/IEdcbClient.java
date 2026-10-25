package app.usecase;

import java.util.List;

/**
 * EDCBのEPG自動予約の操作。検索条件（曖昧検索・アニメ/特撮・全局・録画済みチェック）は件数の確認と登録で共通にする。
 * 通信や応答の解釈に失敗した場合は EdcbException を送出する。
 */
public interface IEdcbClient {

  /** 既存のEPG自動予約のキーワード。 */
  public List<String> autoAddKeywords();

  /** 自動予約と同じ条件でEPGを検索し、一致した番組数を返す。 */
  public int countEvents(String keyword);

  /** EPG自動予約を登録する。録画設定は既定プリセットに揃え、保存先だけを recFolder にする。 */
  public void addAutoAdd(String keyword, String recFolder);
}
